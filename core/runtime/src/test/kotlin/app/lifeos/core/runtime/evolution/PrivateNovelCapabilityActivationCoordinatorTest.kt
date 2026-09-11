package app.lifeos.core.runtime.evolution

import app.lifeos.core.runtime.capability.BoundedGeneratedToolPromotionEvidence
import app.lifeos.core.runtime.capability.BoundedGeneratedToolPromotionReceipt
import app.lifeos.core.runtime.capability.BoundedGeneratedToolStateRepository
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityRegistry
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
import app.lifeos.core.runtime.capability.GeneratedToolRecord
import app.lifeos.core.runtime.capability.GeneratedToolRegistry
import app.lifeos.core.runtime.capability.GeneratedToolRollbackRequest
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PrivateNovelCapabilityActivationCoordinatorTest {
    private val t0 = Instant.parse("2026-09-11T06:00:00Z")

    @Test
    fun `explicit private activation adds five novel trials activates once and rollback preserves trace`() = runTest {
        val durable = MemoryBoundedStateRepository()
        val artifacts = MemoryArtifactRepository(createArtifact())
        val capabilities = CapabilityRegistry()
        val tools = GeneratedToolRegistry(now = { t0.plusSeconds(100) }, durableState = durable)
        registerTrialTool(tools, artifacts.artifact)
        val trials = GeneratedToolTrialLedger(durable)
        val lifecycle = GeneratedToolLifecycleCoordinator(tools, trialLedger = trials, capabilityRegistry = capabilities)
        val runner = GeneratedToolTrialRunner(
            tools = tools,
            lifecycle = lifecycle,
            artifacts = artifacts,
            now = { t0.plusSeconds(10) },
            nanoTime = { 1_000_000L },
        )
        repeat(3) { index ->
            assertIs<GeneratedToolTrialExecutionResult.Completed>(
                runner.execute(
                    GeneratedToolTrialInvocation(
                        TOOL_ID,
                        "initial-${index + 1}",
                        "seed ${index + 1}",
                        "SEED ${index + 1}",
                    )
                )
            )
        }
        val store = InMemoryNovelCapabilityPromotionStore()
        val admission = NovelCapabilityAdmissionGate(capabilities, tools, artifacts, now = { t0.plusSeconds(20) })
        val canary = NovelCapabilityCanaryCoordinator(admission, runner, trials, store, now = { t0.plusSeconds(30) })
        val readiness = NovelCapabilityCanaryReadinessGate(admission, trials, store)
        val promotion = BoundedNovelPromotionCoordinator(
            admissionGate = admission,
            readinessGate = readiness,
            promotionStore = store,
            capabilities = capabilities,
            tools = tools,
            artifacts = artifacts,
            trials = trials,
            lifecycle = lifecycle,
            now = { t0.plusSeconds(40) },
        )
        val coordinator = PrivateNovelCapabilityActivationCoordinator(
            capabilities = capabilities,
            tools = tools,
            artifacts = artifacts,
            trialLedger = trials,
            canary = canary,
            admissionGate = admission,
            readinessGate = readiness,
            promotionStore = store,
            promotion = promotion,
            now = { t0.plusSeconds(40) },
        )

        val activated = assertIs<PrivateNovelCapabilityActivationResult.Activated>(
            coordinator.reviewAndActivate(TOOL_ID, "private-owner")
        )
        assertEquals(5, activated.canaryExecutions.size)
        assertEquals(GeneratedToolState.ACTIVE, activated.promotion.activeRecord.state)
        assertEquals(8, trials.stats(TOOL_ID).trials)
        assertNotNull(durable.state?.boundedPromotionReceipt)
        val provider = capabilities.providersFor(CAPABILITY, includeUnavailable = true).single()
        assertEquals(ProviderType.GENERATED_TOOL, provider.providerType)
        assertEquals(TrustLevel.LOW, provider.trustLevel)

        val second = assertIs<PrivateNovelCapabilityActivationResult.AlreadyActive>(
            coordinator.reviewAndActivate(TOOL_ID, "private-owner")
        )
        assertEquals(TOOL_ID, second.record.manifest.toolId)
        assertEquals(8, trials.stats(TOOL_ID).trials)

        val rollback = lifecycle.rollback(
            GeneratedToolRollbackRequest(
                toolId = TOOL_ID,
                expectedPromotionEvidenceId = activated.promotion.evidence.id,
                actorId = "private-owner",
                evidenceRef = "owner-explicit-rollback",
                reason = "bounded-regression-rollback",
                occurredAt = t0.plusSeconds(120),
            )
        )
        assertEquals(GeneratedToolState.QUARANTINED, rollback.record.state)
        assertEquals(activated.promotion.evidence.id, rollback.record.promotionEvidenceId)
        assertTrue(capabilities.providersFor(CAPABILITY, includeUnavailable = true).isEmpty())
        val persisted = assertNotNull(durable.state)
        assertEquals(GeneratedToolAuditAction.ROLLED_BACK, persisted.auditEntries.last().action)
        val preservedReceipt = assertNotNull(persisted.boundedPromotionReceipt)
        assertEquals(activated.promotion.evidence.id, preservedReceipt.evidenceId)
        assertEquals(activated.promotion.seal.id, preservedReceipt.promotionSealId)
    }

    private fun createArtifact(): GeneratedToolArtifact {
        val program = GeneratedToolProgram(
            toolId = TOOL_ID,
            capabilityId = CAPABILITY,
            requiredInputs = setOf("text"),
            requiredOutputs = setOf("upper-text"),
            instructions = listOf(GeneratedToolInstruction(GeneratedToolOpcode.UPPERCASE)),
        )
        return GeneratedToolArtifact.create(TOOL_ID, GeneratedToolProgramCodec.encode(program), t0)
    }

    private suspend fun registerTrialTool(tools: GeneratedToolRegistry, artifact: GeneratedToolArtifact) {
        tools.register(
            GeneratedToolRecord(
                GeneratedToolManifest(
                    toolId = TOOL_ID,
                    sourceCapability = CAPABILITY,
                    sourceHash = artifact.sourceHash,
                    buildHash = artifact.buildHash,
                    permissions = emptySet(),
                    generatedAt = t0,
                    requiredInputs = setOf("text"),
                    requiredOutputs = setOf("upper-text"),
                ),
                GeneratedToolState.GENERATED,
            )
        )
        tools.transition(TOOL_ID, GeneratedToolState.BUILT, message = "built")
        tools.transition(TOOL_ID, GeneratedToolState.TESTED, message = "tested")
        tools.transition(TOOL_ID, GeneratedToolState.VERIFIED, confidence = 0.95, message = "verified")
        tools.transition(TOOL_ID, GeneratedToolState.TRIAL, message = "trial")
    }

    private class MemoryArtifactRepository(val artifact: GeneratedToolArtifact) : GeneratedToolArtifactRepository {
        override suspend fun persist(artifact: GeneratedToolArtifact) = error("read-only")
        override suspend fun load(toolId: String): GeneratedToolArtifact? = artifact.takeIf { it.toolId == toolId }
        override suspend fun loadAll(): List<GeneratedToolArtifact> = listOf(artifact)
    }

    private class MemoryBoundedStateRepository : GeneratedToolStateRepository, BoundedGeneratedToolStateRepository {
        var state: GeneratedToolPersistentState? = null
            private set

        override suspend fun loadAll(): List<GeneratedToolPersistentState> = listOfNotNull(state)

        override suspend fun persistLifecycle(
            record: GeneratedToolRecord,
            auditEntries: List<GeneratedToolAuditEntry>,
            promotionEvidence: GeneratedToolPromotionEvidence?,
        ) {
            val existing = state
            val trials = existing?.trialEvidence ?: GeneratedToolTrialEvidence(record.manifest.toolId, emptyList())
            val legacyReceipt = promotionEvidence
                ?.let { app.lifeos.core.runtime.capability.GeneratedToolPromotionReceipt.from(it) }
                ?: existing?.promotionReceipt?.takeIf { record.promotionEvidenceId == it.evidenceId }
            val boundedReceipt = existing?.boundedPromotionReceipt
                ?.takeIf { record.promotionEvidenceId == it.evidenceId && promotionEvidence == null }
            state = GeneratedToolPersistentState(
                record = record,
                auditEntries = auditEntries,
                trialEvidence = trials,
                promotionReceipt = legacyReceipt,
                boundedPromotionReceipt = boundedReceipt,
            )
        }

        override suspend fun persistTrialEvidence(evidence: GeneratedToolTrialEvidence) {
            val current = requireNotNull(state)
            state = current.copy(trialEvidence = evidence)
        }

        override suspend fun persistBoundedLifecycle(
            record: GeneratedToolRecord,
            auditEntries: List<GeneratedToolAuditEntry>,
            promotionEvidence: BoundedGeneratedToolPromotionEvidence,
        ) {
            val current = requireNotNull(state)
            state = GeneratedToolPersistentState(
                record = record,
                auditEntries = auditEntries,
                trialEvidence = current.trialEvidence,
                boundedPromotionReceipt = BoundedGeneratedToolPromotionReceipt(
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
                ),
            )
        }
    }

    private companion object {
        val CAPABILITY = CapabilityId("text.uppercase.local")
        const val TOOL_ID = "tool-private-activation"
    }
}
