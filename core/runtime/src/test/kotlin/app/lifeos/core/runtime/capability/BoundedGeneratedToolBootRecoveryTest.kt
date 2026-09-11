package app.lifeos.core.runtime.capability

import app.lifeos.core.runtime.buildstudio.BuildActorAction
import app.lifeos.core.runtime.buildstudio.BuildActorEvidence
import app.lifeos.core.runtime.buildstudio.BuildActorRole
import app.lifeos.core.runtime.evolution.InMemoryNovelCapabilityPromotionStore
import app.lifeos.core.runtime.evolution.NovelCapabilityCanaryOutcome
import app.lifeos.core.runtime.evolution.NovelCapabilityCanaryOutcomeWriteResult
import app.lifeos.core.runtime.evolution.NovelCapabilityCanaryReservationRequest
import app.lifeos.core.runtime.evolution.NovelCapabilityCanaryReserveResult
import app.lifeos.core.runtime.evolution.evolutionFingerprint
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BoundedGeneratedToolBootRecoveryTest {
    private val t0 = Instant.parse("2026-09-11T05:00:00Z")

    @Test
    fun `fresh process restores bounded active only with exact artifact seal outcomes and trials`() = runTest {
        val fixture = durableFixture()
        val tools = GeneratedToolRegistry()
        val trials = GeneratedToolTrialLedger()
        val capabilities = CapabilityRegistry()
        val policy = v14AllowProviderRestore(t0)
        val restoreAuthority = GeneratedProviderRestoreAuthority(
            ownerPolicy = policy.ledger,
            actorId = V14_TEST_OWNER,
            scope = V14_TEST_RESTORE_SCOPE,
        )

        val report = GeneratedToolBootStateRehydrator(
            repository = fixture.states,
            tools = tools,
            trialLedger = trials,
            capabilityRegistry = capabilities,
            artifactRepository = fixture.artifacts,
            novelPromotionStore = fixture.promotionStore,
            providerRestoreAuthority = restoreAuthority,
        ).rehydrateOrVerify()

        assertEquals(1, report.restoredTools)
        assertEquals(8, report.restoredTrialResults)
        assertEquals(1, report.restoredActiveProviders)
        assertEquals(GeneratedToolState.ACTIVE, tools.get(TOOL_ID)?.state)
        assertEquals(fixture.state.trialEvidence, trials.evidence(TOOL_ID))
        assertTrue(tools.verifyAuditChain(TOOL_ID))
        val provider = capabilities.providersFor(CAPABILITY, includeUnavailable = true).single()
        assertEquals(ProviderType.GENERATED_TOOL, provider.providerType)
        assertEquals(TrustLevel.LOW, provider.trustLevel)
        assertEquals(1.0, provider.reliability)

        val retry = GeneratedToolBootStateRehydrator(
            repository = fixture.states,
            tools = tools,
            trialLedger = trials,
            capabilityRegistry = capabilities,
            artifactRepository = fixture.artifacts,
            novelPromotionStore = fixture.promotionStore,
            providerRestoreAuthority = restoreAuthority,
        ).rehydrateOrVerify()
        assertEquals(report, retry)
    }

    @Test
    fun `missing durable novel seal blocks bounded active before any ram restore`() = runTest {
        val fixture = durableFixture()
        val tools = GeneratedToolRegistry()
        val trials = GeneratedToolTrialLedger()
        val capabilities = CapabilityRegistry()
        val policy = v14AllowProviderRestore(t0)

        val failure = assertFailsWith<IllegalArgumentException> {
            GeneratedToolBootStateRehydrator(
                repository = fixture.states,
                tools = tools,
                trialLedger = trials,
                capabilityRegistry = capabilities,
                artifactRepository = fixture.artifacts,
                novelPromotionStore = InMemoryNovelCapabilityPromotionStore(),
                providerRestoreAuthority = GeneratedProviderRestoreAuthority(
                    ownerPolicy = policy.ledger,
                    actorId = V14_TEST_OWNER,
                    scope = V14_TEST_RESTORE_SCOPE,
                ),
            ).rehydrateOrVerify()
        }

        assertTrue(failure.message.orEmpty().contains("promotion seal"))
        assertTrue(tools.snapshot().isEmpty())
        assertEquals(0, trials.evidence(TOOL_ID).stats.trials)
        assertTrue(capabilities.all(includeUnavailable = true).isEmpty())
    }

    private suspend fun durableFixture(): Fixture {
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
        val manifest = GeneratedToolManifest(
            toolId = TOOL_ID,
            sourceCapability = CAPABILITY,
            sourceHash = artifact.sourceHash,
            buildHash = artifact.buildHash,
            permissions = emptySet(),
            generatedAt = t0,
            requiredInputs = setOf("text"),
            requiredOutputs = setOf("upper-text"),
        )
        val generated = GeneratedToolRecord(manifest, GeneratedToolState.GENERATED)
        val built = generated.copy(state = GeneratedToolState.BUILT, lastMessage = "built")
        val tested = built.copy(state = GeneratedToolState.TESTED, lastMessage = "tested")
        val verified = tested.copy(
            state = GeneratedToolState.VERIFIED,
            verificationConfidence = 0.95,
            lastMessage = "verified",
        )
        val trial = verified.copy(state = GeneratedToolState.TRIAL, lastMessage = "trial")

        val trialResults = buildList {
            repeat(3) { index ->
                add(
                    GeneratedToolTrialResult(
                        invocationId = "$TOOL_ID/private-trial-v1/initial-${index + 1}",
                        success = true,
                        producedExpectedOutput = true,
                        latencyMs = 2,
                        recordedAt = t0.plusSeconds(10L + index),
                    )
                )
            }
            repeat(5) { index ->
                add(
                    GeneratedToolTrialResult(
                        invocationId = "$TOOL_ID/private-novel-canary-v1/case-${index + 1}",
                        success = true,
                        producedExpectedOutput = true,
                        latencyMs = 3,
                        recordedAt = t0.plusSeconds(20L + index),
                    )
                )
            }
        }
        val trialEvidence = GeneratedToolTrialEvidence(TOOL_ID, trialResults)

        val promotionStore = InMemoryNovelCapabilityPromotionStore()
        val candidateFingerprint = trial.evolutionFingerprint()
        val admissionId = "admission-boot-bounded"
        val readinessId = "readiness-boot-bounded"
        val canaryResults = trialResults.takeLast(5)
        for ((index, result) in canaryResults.withIndex()) {
            val reserve = promotionStore.reserveNovel(
                NovelCapabilityCanaryReservationRequest(
                    admissionEvidenceId = admissionId,
                    toolId = TOOL_ID,
                    candidateRecordFingerprint = candidateFingerprint,
                    invocationId = result.invocationId,
                    inputFingerprint = "input-${index + 1}",
                    expectedOutputFingerprint = "expected-${index + 1}",
                    maxInvocations = 5,
                    reservedAt = t0.plusSeconds(30L + index),
                )
            )
            val reservation = (reserve as NovelCapabilityCanaryReserveResult.Reserved).reservation
            val write = promotionStore.recordNovelOutcome(
                NovelCapabilityCanaryOutcome(
                    admissionEvidenceId = admissionId,
                    reservationId = reservation.id,
                    toolId = TOOL_ID,
                    candidateRecordFingerprint = candidateFingerprint,
                    invocationId = result.invocationId,
                    trialResultFingerprint = result.fingerprint(),
                    success = true,
                    producedExpectedOutput = true,
                    safetyViolation = false,
                    latencyMs = result.latencyMs,
                    recordedAt = result.recordedAt,
                )
            )
            assertTrue(write is NovelCapabilityCanaryOutcomeWriteResult.Recorded)
        }
        val seal = promotionStore.sealNovelForPromotion(
            admissionEvidenceId = admissionId,
            subjectId = "subject-boot-bounded",
            toolId = TOOL_ID,
            candidateRecordFingerprint = candidateFingerprint,
            artifactId = artifact.id,
            readinessEvidenceId = readinessId,
            expectedReservedInvocations = 5,
            sealedAt = t0.plusSeconds(40),
        )

        val reviewer = BuildActorEvidence(
            actorId = "lifeos-private-novel-readiness-reviewer",
            role = BuildActorRole.REVIEWER,
            action = BuildActorAction.APPROVED,
            occurredAt = t0.plusSeconds(41),
            evidenceRef = readinessId,
        )
        val activator = BuildActorEvidence(
            actorId = "private-owner",
            role = BuildActorRole.PROMOTION_ACTOR,
            action = BuildActorAction.PROMOTED,
            occurredAt = t0.plusSeconds(42),
            evidenceRef = seal.id,
        )
        val reviewerFingerprints = listOf(reviewer.fingerprint())
        val activationFingerprints = listOf(activator.fingerprint())
        val policyFingerprint = GeneratedToolPromotionPolicy().fingerprint()
        val evidenceId = boundedGeneratedToolPromotionEvidenceId(
            toolId = TOOL_ID,
            artifactId = artifact.id,
            recordFingerprint = trial.promotionRecordFingerprint(),
            trialEvidenceId = trialEvidence.id,
            promotionPolicyFingerprint = policyFingerprint,
            novelAdmissionEvidenceId = admissionId,
            canaryReadinessEvidenceId = readinessId,
            promotionSealId = seal.id,
            reviewerEvidenceFingerprints = reviewerFingerprints,
            activationActorEvidenceFingerprints = activationFingerprints,
        )
        val receipt = BoundedGeneratedToolPromotionReceipt(
            evidenceId = evidenceId,
            toolId = TOOL_ID,
            artifactId = artifact.id,
            recordFingerprint = trial.promotionRecordFingerprint(),
            trialEvidenceId = trialEvidence.id,
            promotionPolicyFingerprint = policyFingerprint,
            novelAdmissionEvidenceId = admissionId,
            canaryReadinessEvidenceId = readinessId,
            promotionSealId = seal.id,
            reviewerEvidenceFingerprints = reviewerFingerprints,
            activationActorEvidenceFingerprints = activationFingerprints,
        )
        val active = trial.copy(
            state = GeneratedToolState.ACTIVE,
            lastMessage = "bounded-trial-promoted:$evidenceId",
            promotionEvidenceId = evidenceId,
        )
        val audit = auditChain(generated, built, tested, verified, trial, active, evidenceId)
        val state = GeneratedToolPersistentState(
            record = active,
            auditEntries = audit,
            trialEvidence = trialEvidence,
            boundedPromotionReceipt = receipt,
        )
        return Fixture(
            state = state,
            states = StaticStateRepository(listOf(state)),
            artifacts = StaticArtifactRepository(artifact),
            promotionStore = promotionStore,
        )
    }

    private fun auditChain(
        generated: GeneratedToolRecord,
        built: GeneratedToolRecord,
        tested: GeneratedToolRecord,
        verified: GeneratedToolRecord,
        trial: GeneratedToolRecord,
        active: GeneratedToolRecord,
        evidenceId: String,
    ): List<GeneratedToolAuditEntry> {
        val entries = mutableListOf<GeneratedToolAuditEntry>()
        fun append(
            before: GeneratedToolRecord?,
            after: GeneratedToolRecord,
            action: GeneratedToolAuditAction,
            reason: String? = after.lastMessage,
            actorId: String? = null,
            evidenceRef: String? = null,
        ) {
            val previous = entries.lastOrNull()
            entries += GeneratedToolAuditEntry(
                toolId = TOOL_ID,
                action = action,
                fromState = before?.state,
                toState = after.state,
                beforeRecordFingerprint = before?.auditFingerprint(),
                afterRecordFingerprint = after.auditFingerprint(),
                actorId = actorId,
                evidenceRef = evidenceRef,
                reason = reason,
                occurredAt = t0.plusSeconds(entries.size.toLong()),
                previousEntryId = previous?.id,
            )
        }
        append(null, generated, GeneratedToolAuditAction.REGISTERED, reason = null)
        append(generated, built, GeneratedToolAuditAction.TRANSITIONED)
        append(built, tested, GeneratedToolAuditAction.TRANSITIONED)
        append(tested, verified, GeneratedToolAuditAction.TRANSITIONED)
        append(verified, trial, GeneratedToolAuditAction.TRANSITIONED)
        append(
            trial,
            active,
            GeneratedToolAuditAction.PROMOTED,
            reason = "bounded-generated-tool-promotion-evidence:$evidenceId",
            actorId = "private-owner",
            evidenceRef = evidenceId,
        )
        return entries
    }

    private data class Fixture(
        val state: GeneratedToolPersistentState,
        val states: GeneratedToolStateRepository,
        val artifacts: GeneratedToolArtifactRepository,
        val promotionStore: InMemoryNovelCapabilityPromotionStore,
    )

    private class StaticStateRepository(
        private val states: List<GeneratedToolPersistentState>,
    ) : GeneratedToolStateRepository {
        override suspend fun loadAll(): List<GeneratedToolPersistentState> = states
        override suspend fun persistLifecycle(
            record: GeneratedToolRecord,
            auditEntries: List<GeneratedToolAuditEntry>,
            promotionEvidence: GeneratedToolPromotionEvidence?,
        ) = error("read-only boot fixture")
        override suspend fun persistTrialEvidence(evidence: GeneratedToolTrialEvidence) =
            error("read-only boot fixture")
    }

    private class StaticArtifactRepository(
        private val artifact: GeneratedToolArtifact,
    ) : GeneratedToolArtifactRepository {
        override suspend fun persist(artifact: GeneratedToolArtifact) = error("read-only boot fixture")
        override suspend fun load(toolId: String): GeneratedToolArtifact? = artifact.takeIf { it.toolId == toolId }
        override suspend fun loadAll(): List<GeneratedToolArtifact> = listOf(artifact)
    }

    private companion object {
        val CAPABILITY = CapabilityId("text.uppercase.local")
        const val TOOL_ID = "tool-bounded-boot"
    }
}
