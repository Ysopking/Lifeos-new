package app.lifeos.core.runtime.evolution

import app.lifeos.core.runtime.buildstudio.BuildActorAction
import app.lifeos.core.runtime.buildstudio.BuildActorEvidence
import app.lifeos.core.runtime.buildstudio.BuildActorRole
import app.lifeos.core.runtime.capability.BoundedGeneratedToolPromotionEvidence
import app.lifeos.core.runtime.capability.BoundedGeneratedToolPromotionReceipt
import app.lifeos.core.runtime.capability.BoundedGeneratedToolStateRepository
import app.lifeos.core.runtime.capability.CapabilityGapDetector
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityRegistry
import app.lifeos.core.runtime.capability.CapabilityRequirement
import app.lifeos.core.runtime.capability.GeneratedToolArtifact
import app.lifeos.core.runtime.capability.GeneratedToolArtifactRepository
import app.lifeos.core.runtime.capability.GeneratedToolAuditAction
import app.lifeos.core.runtime.capability.GeneratedToolAuditEntry
import app.lifeos.core.runtime.capability.GeneratedToolInstruction
import app.lifeos.core.runtime.capability.GeneratedToolLifecycleCoordinator
import app.lifeos.core.runtime.capability.GeneratedToolManifest
import app.lifeos.core.runtime.capability.GeneratedToolOpcode
import app.lifeos.core.runtime.capability.GeneratedToolPersistentState
import app.lifeos.core.runtime.capability.GeneratedToolProgram
import app.lifeos.core.runtime.capability.GeneratedToolProgramCodec
import app.lifeos.core.runtime.capability.GeneratedToolPromotionEvidence
import app.lifeos.core.runtime.capability.GeneratedToolPromotionReceipt
import app.lifeos.core.runtime.capability.GeneratedToolRecord
import app.lifeos.core.runtime.capability.GeneratedToolRegistry
import app.lifeos.core.runtime.capability.GeneratedToolState
import app.lifeos.core.runtime.capability.GeneratedToolStateRepository
import app.lifeos.core.runtime.capability.GeneratedToolTrialEvidence
import app.lifeos.core.runtime.capability.GeneratedToolTrialExecutionResult
import app.lifeos.core.runtime.capability.GeneratedToolTrialInvocation
import app.lifeos.core.runtime.capability.GeneratedToolTrialLedger
import app.lifeos.core.runtime.capability.GeneratedToolTrialRunner
import app.lifeos.core.runtime.capability.ProviderType
import app.lifeos.core.runtime.capability.TrustLevel
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BoundedNovelPromotionTest {
    private val t0 = Instant.parse("2026-09-11T04:00:00Z")

    @Test
    fun `bounded novel promotion persists receipt before active and registers one low trust provider`() = runTest {
        val durable = MemoryBoundedStateRepository()
        val fixture = fixture(durable)
        val review = fixture.prepareReview()
        val actors = actors(review.readiness, review.seal)

        val result = fixture.promotion.promote(fixture.subject, fixture.admission, actors)

        assertEquals(GeneratedToolState.ACTIVE, result.activeRecord.state)
        assertEquals(result.evidence.id, result.activeRecord.promotionEvidenceId)
        assertFalse(result.evidence.activationAllowed)
        assertEquals(review.seal, result.seal)
        assertEquals(8, fixture.ledger.stats(TOOL_ID).trials)

        val persisted = assertNotNull(durable.state)
        assertEquals(GeneratedToolState.ACTIVE, persisted.record.state)
        val receipt = assertNotNull(persisted.boundedPromotionReceipt)
        assertEquals(result.evidence.id, receipt.evidenceId)
        assertEquals(result.evidence.artifactId, receipt.artifactId)
        assertEquals(result.evidence.trialEvidenceId, receipt.trialEvidenceId)
        assertEquals(GeneratedToolAuditAction.PROMOTED, persisted.auditEntries.last().action)
        assertEquals("bounded-generated-tool-promotion-evidence:${result.evidence.id}", persisted.auditEntries.last().reason)

        val providers = fixture.capabilities.providersFor(CAPABILITY, includeUnavailable = true)
        assertEquals(1, providers.size)
        assertEquals(TOOL_ID, providers.single().providerId)
        assertEquals(ProviderType.GENERATED_TOOL, providers.single().providerType)
        assertEquals(TrustLevel.LOW, providers.single().trustLevel)
        assertEquals(1.0, providers.single().reliability)
        assertTrue(fixture.tools.verifyAuditChain(TOOL_ID))
    }

    @Test
    fun `bounded promotion fails before ram active when durable repository lacks bounded receipt support`() = runTest {
        val durable = MemoryLegacyStateRepository()
        val fixture = fixture(durable)
        val review = fixture.prepareReview()

        val failure = assertFailsWith<IllegalStateException> {
            fixture.promotion.promote(fixture.subject, fixture.admission, actors(review.readiness, review.seal))
        }

        assertTrue(failure.message.orEmpty().contains("bounded durable state repository"))
        assertEquals(GeneratedToolState.TRIAL, fixture.tools.get(TOOL_ID)?.state)
        assertTrue(fixture.capabilities.providersFor(CAPABILITY, includeUnavailable = true).isEmpty())
        assertEquals(GeneratedToolState.TRIAL, durable.state?.record?.state)
        assertEquals(null, durable.state?.boundedPromotionReceipt)
    }

    @Test
    fun `reviewer and activation actor identities must remain separated`() = runTest {
        val durable = MemoryBoundedStateRepository()
        val fixture = fixture(durable)
        val review = fixture.prepareReview()
        val sameActor = listOf(
            BuildActorEvidence(
                actorId = "owner",
                role = BuildActorRole.REVIEWER,
                action = BuildActorAction.APPROVED,
                occurredAt = review.seal.sealedAt.plusSeconds(1),
                evidenceRef = review.readiness.id,
            ),
            BuildActorEvidence(
                actorId = "owner",
                role = BuildActorRole.PROMOTION_ACTOR,
                action = BuildActorAction.PROMOTED,
                occurredAt = review.seal.sealedAt.plusSeconds(2),
                evidenceRef = review.seal.id,
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            fixture.promotion.promote(fixture.subject, fixture.admission, sameActor)
        }

        assertEquals(GeneratedToolState.TRIAL, fixture.tools.get(TOOL_ID)?.state)
        assertTrue(fixture.capabilities.providersFor(CAPABILITY, includeUnavailable = true).isEmpty())
        assertEquals(GeneratedToolState.TRIAL, durable.state?.record?.state)
    }

    @Test
    fun `sealed novel canary refuses further reservations`() = runTest {
        val fixture = fixture(MemoryBoundedStateRepository())
        val review = fixture.prepareReview()

        assertEquals(review.seal, fixture.store.novelPromotionSeal(fixture.admission.id))
        assertFailsWith<IllegalArgumentException> {
            fixture.store.reserveNovel(
                NovelCapabilityCanaryReservationRequest(
                    admissionEvidenceId = fixture.admission.id,
                    toolId = TOOL_ID,
                    candidateRecordFingerprint = fixture.subject.candidateRecordFingerprint,
                    invocationId = "after-seal",
                    inputFingerprint = "input",
                    expectedOutputFingerprint = "expected",
                    maxInvocations = 6,
                    reservedAt = review.seal.sealedAt.plusSeconds(1),
                )
            )
        }
        assertEquals(GeneratedToolState.TRIAL, fixture.tools.get(TOOL_ID)?.state)
    }

    private suspend fun fixture(durable: GeneratedToolStateRepository): Fixture {
        val program = GeneratedToolProgram(
            toolId = TOOL_ID,
            capabilityId = CAPABILITY,
            requiredInputs = setOf("text"),
            requiredOutputs = setOf("upper-text"),
            instructions = listOf(GeneratedToolInstruction(GeneratedToolOpcode.UPPERCASE)),
        )
        val artifact = GeneratedToolArtifact.create(
            toolId = TOOL_ID,
            canonicalProgram = GeneratedToolProgramCodec.encode(program),
            createdAt = t0,
        )
        val artifactRepository = MemoryArtifactRepository(artifact)
        val capabilities = CapabilityRegistry()
        val tools = GeneratedToolRegistry(now = { t0.plusSeconds(200) }, durableState = durable)
        tools.register(
            GeneratedToolRecord(
                manifest = GeneratedToolManifest(
                    toolId = TOOL_ID,
                    sourceCapability = CAPABILITY,
                    sourceHash = artifact.sourceHash,
                    buildHash = artifact.buildHash,
                    permissions = emptySet(),
                    generatedAt = t0,
                    requiredInputs = setOf("text"),
                    requiredOutputs = setOf("upper-text"),
                ),
                state = GeneratedToolState.GENERATED,
            )
        )
        tools.transition(TOOL_ID, GeneratedToolState.BUILT, message = "built")
        tools.transition(TOOL_ID, GeneratedToolState.TESTED, message = "tested")
        tools.transition(TOOL_ID, GeneratedToolState.VERIFIED, confidence = 0.95, message = "verified")
        tools.transition(TOOL_ID, GeneratedToolState.TRIAL, message = "trial")

        val ledger = GeneratedToolTrialLedger(durable)
        val lifecycle = GeneratedToolLifecycleCoordinator(
            tools = tools,
            trialLedger = ledger,
            capabilityRegistry = capabilities,
        )
        val runner = GeneratedToolTrialRunner(
            tools = tools,
            lifecycle = lifecycle,
            artifacts = artifactRepository,
            now = { t0.plusSeconds(10) },
            nanoTime = { 1_000_000L },
        )
        repeat(3) { index ->
            assertIs<GeneratedToolTrialExecutionResult.Completed>(
                runner.execute(invocation("initial-${index + 1}", "seed ${index + 1}", "SEED ${index + 1}"))
            )
        }

        val requirement = CapabilityRequirement(
            capabilityId = CAPABILITY,
            requiredInputs = setOf("text"),
            requiredOutputs = setOf("upper-text"),
        )
        val gap = requireNotNull(CapabilityGapDetector(capabilities).detect(requirement))
        val subject = NovelCapabilityAdmissionSubject.create(gap, requireNotNull(tools.get(TOOL_ID)), artifact)
        val gate = NovelCapabilityAdmissionGate(
            capabilities = capabilities,
            tools = tools,
            artifacts = artifactRepository,
            now = { t0.plusSeconds(20) },
        )
        val admission = gate.evaluate(subject)
        val store = InMemoryNovelCapabilityPromotionStore()
        val canary = NovelCapabilityCanaryCoordinator(
            admissionGate = gate,
            trialRunner = runner,
            trialLedger = ledger,
            store = store,
            now = { t0.plusSeconds(30) },
        )
        repeat(5) { index ->
            assertIs<NovelCapabilityCanaryExecutionResult.Executed>(
                canary.execute(
                    subject,
                    admission,
                    invocation("canary-${index + 1}", "value ${index + 1}", "VALUE ${index + 1}"),
                )
            )
        }
        val readiness = NovelCapabilityCanaryReadinessGate(gate, ledger, store)
        val promotion = BoundedNovelPromotionCoordinator(
            admissionGate = gate,
            readinessGate = readiness,
            promotionStore = store,
            capabilities = capabilities,
            tools = tools,
            artifacts = artifactRepository,
            trials = ledger,
            lifecycle = lifecycle,
            now = { t0.plusSeconds(100) },
        )
        return Fixture(tools, ledger, capabilities, subject, admission, gate, store, readiness, promotion)
    }

    private suspend fun Fixture.prepareReview(): ReviewBundle {
        val currentReadiness = readiness.evaluate(subject, admission)
        assertEquals(NovelCapabilityCanaryReadinessDecision.READY_FOR_REVIEW, currentReadiness.decision)
        val seal = store.sealNovelForPromotion(
            admissionEvidenceId = admission.id,
            subjectId = subject.id,
            toolId = subject.toolId,
            candidateRecordFingerprint = subject.candidateRecordFingerprint,
            artifactId = subject.artifactId,
            readinessEvidenceId = currentReadiness.id,
            expectedReservedInvocations = currentReadiness.reservedInvocations,
            sealedAt = t0.plusSeconds(100),
        )
        return ReviewBundle(currentReadiness, seal)
    }

    private fun actors(
        readiness: NovelCapabilityCanaryReadinessEvidence,
        seal: NovelCapabilityPromotionSealEvidence,
    ): List<BuildActorEvidence> = listOf(
        BuildActorEvidence(
            actorId = "reviewer-owner",
            role = BuildActorRole.REVIEWER,
            action = BuildActorAction.APPROVED,
            occurredAt = seal.sealedAt.plusSeconds(1),
            evidenceRef = readiness.id,
        ),
        BuildActorEvidence(
            actorId = "activation-owner",
            role = BuildActorRole.PROMOTION_ACTOR,
            action = BuildActorAction.PROMOTED,
            occurredAt = seal.sealedAt.plusSeconds(2),
            evidenceRef = seal.id,
        ),
    )

    private fun invocation(id: String, input: String, expected: String) = GeneratedToolTrialInvocation(
        toolId = TOOL_ID,
        invocationId = id,
        input = input,
        expectedOutput = expected,
    )

    private data class ReviewBundle(
        val readiness: NovelCapabilityCanaryReadinessEvidence,
        val seal: NovelCapabilityPromotionSealEvidence,
    )

    private data class Fixture(
        val tools: GeneratedToolRegistry,
        val ledger: GeneratedToolTrialLedger,
        val capabilities: CapabilityRegistry,
        val subject: NovelCapabilityAdmissionSubject,
        val admission: NovelCapabilityAdmissionEvidence,
        val gate: NovelCapabilityAdmissionGate,
        val store: InMemoryNovelCapabilityPromotionStore,
        val readiness: NovelCapabilityCanaryReadinessGate,
        val promotion: BoundedNovelPromotionCoordinator,
    )

    private class MemoryArtifactRepository(
        private val artifact: GeneratedToolArtifact,
    ) : GeneratedToolArtifactRepository {
        override suspend fun persist(artifact: GeneratedToolArtifact) = error("read-only test artifact repository")
        override suspend fun load(toolId: String): GeneratedToolArtifact? = artifact.takeIf { it.toolId == toolId }
        override suspend fun loadAll(): List<GeneratedToolArtifact> = listOf(artifact)
    }

    private open class MemoryLegacyStateRepository : GeneratedToolStateRepository {
        var state: GeneratedToolPersistentState? = null
            protected set

        override suspend fun loadAll(): List<GeneratedToolPersistentState> = listOfNotNull(state)

        override suspend fun persistLifecycle(
            record: GeneratedToolRecord,
            auditEntries: List<GeneratedToolAuditEntry>,
            promotionEvidence: GeneratedToolPromotionEvidence?,
        ) {
            val trials = state?.trialEvidence ?: GeneratedToolTrialEvidence(record.manifest.toolId, emptyList())
            val receipt = promotionEvidence?.let(GeneratedToolPromotionReceipt::from)
            state = GeneratedToolPersistentState(
                record = record,
                auditEntries = auditEntries,
                trialEvidence = trials,
                promotionReceipt = receipt,
            )
        }

        override suspend fun persistTrialEvidence(evidence: GeneratedToolTrialEvidence) {
            val current = requireNotNull(state)
            state = current.copy(trialEvidence = evidence)
        }
    }

    private class MemoryBoundedStateRepository : MemoryLegacyStateRepository(), BoundedGeneratedToolStateRepository {
        override suspend fun persistBoundedLifecycle(
            record: GeneratedToolRecord,
            auditEntries: List<GeneratedToolAuditEntry>,
            promotionEvidence: BoundedGeneratedToolPromotionEvidence,
        ) {
            val current = requireNotNull(state)
            val receipt = BoundedGeneratedToolPromotionReceipt(
                evidenceId = promotionEvidence.id,
                toolId = promotionEvidence.toolId,
                artifactId = promotionEvidence.artifactId,
                recordFingerprint = promotionEvidence.recordFingerprint,
                trialEvidenceId = promotionEvidence.trialEvidenceId,
                promotionPolicyFingerprint = promotionEvidence.promotionPolicyFingerprint,
                novelAdmissionEvidenceId = promotionEvidence.novelAdmissionEvidenceId,
                canaryReadinessEvidenceId = promotionEvidence.canaryReadinessEvidenceId,
                promotionSealId = promotionEvidence.promotionSealId,
                reviewerEvidenceFingerprints = promotionEvidence.reviewerEvidenceFingerprints,
                activationActorEvidenceFingerprints = promotionEvidence.activationActorEvidenceFingerprints,
            )
            state = GeneratedToolPersistentState(
                record = record,
                auditEntries = auditEntries,
                trialEvidence = current.trialEvidence,
                boundedPromotionReceipt = receipt,
            )
        }
    }

    private companion object {
        val CAPABILITY = CapabilityId("text.uppercase.local")
        const val TOOL_ID = "tool-bounded-uppercase"
    }
}
