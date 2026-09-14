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
import app.lifeos.core.runtime.resource.ResourceBudgetAccount
import app.lifeos.core.runtime.resource.ResourceBudgetAccountId
import app.lifeos.core.runtime.resource.ResourceBudgetCoordinator
import app.lifeos.core.runtime.resource.ResourceBudgetDomain
import app.lifeos.core.runtime.resource.ResourceBudgetRepository
import app.lifeos.core.runtime.resource.ResourceBudgetRepositoryLoadReport
import app.lifeos.core.runtime.resource.ResourceBudgetReservationState
import app.lifeos.core.runtime.resource.ResourceBudgetUsage
import app.lifeos.core.runtime.resource.SharedResourceBudgetDecision
import app.lifeos.core.runtime.resource.SharedResourceBudgetGate
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
    fun `explicit private activation adds five novel trials settles V16 and rollback preserves trace`() = runTest {
        val budgetRepository = MemoryResourceBudgetRepository()
        val budgets = ResourceBudgetCoordinator(budgetRepository) { t0.plusSeconds(35) }
        val fixture = fixture(
            budgets = budgets,
            sharedBudgetsProvider = { null },
        )

        val activated = assertIs<PrivateNovelCapabilityActivationResult.Activated>(
            fixture.coordinator.reviewAndActivate(TOOL_ID, "private-owner")
        )
        assertEquals(5, activated.canaryExecutions.size)
        assertEquals(GeneratedToolState.ACTIVE, activated.promotion.activeRecord.state)
        assertEquals(8, fixture.trials.stats(TOOL_ID).trials)
        assertNotNull(fixture.durable.state?.boundedPromotionReceipt)
        val provider = fixture.capabilities.providersFor(CAPABILITY, includeUnavailable = true).single()
        assertEquals(ProviderType.GENERATED_TOOL, provider.providerType)
        assertEquals(TrustLevel.LOW, provider.trustLevel)

        val budget = budgetRepository.accounts.values.single()
        assertEquals(5, budget.reservations.size)
        assertTrue(budget.reservations.all { it.state == ResourceBudgetReservationState.COMMITTED })
        assertEquals(5L, budget.consumed.workUnits)
        assertEquals(5L, budget.consumed.candidates)

        val second = assertIs<PrivateNovelCapabilityActivationResult.AlreadyActive>(
            fixture.coordinator.reviewAndActivate(TOOL_ID, "private-owner")
        )
        assertEquals(TOOL_ID, second.record.manifest.toolId)
        assertEquals(8, fixture.trials.stats(TOOL_ID).trials)
        assertEquals(budget.consumed, budgetRepository.accounts.values.single().consumed)

        val rollback = fixture.lifecycle.rollback(
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
        assertTrue(fixture.capabilities.providersFor(CAPABILITY, includeUnavailable = true).isEmpty())
        val persisted = assertNotNull(fixture.durable.state)
        assertEquals(GeneratedToolAuditAction.ROLLED_BACK, persisted.auditEntries.last().action)
        val preservedReceipt = assertNotNull(persisted.boundedPromotionReceipt)
        assertEquals(activated.promotion.evidence.id, preservedReceipt.evidenceId)
        assertEquals(activated.promotion.seal.id, preservedReceipt.promotionSealId)
    }

    @Test
    fun `V16 World Formula block prevents novel canary trial and reservation`() = runTest {
        val budgetRepository = MemoryResourceBudgetRepository()
        val budgets = ResourceBudgetCoordinator(budgetRepository) { t0.plusSeconds(35) }
        var allocationCalls = 0
        val blockedGate = SharedResourceBudgetGate { _, demands ->
            allocationCalls += 1
            assertEquals(listOf(ResourceBudgetDomain.EVOLUTION), demands.map { it.domain })
            SharedResourceBudgetDecision.Blocked("test-hardware-suspended")
        }
        val fixture = fixture(
            budgets = budgets,
            sharedBudgetsProvider = { blockedGate },
        )

        val blocked = assertIs<PrivateNovelCapabilityActivationResult.Blocked>(
            fixture.coordinator.reviewAndActivate(TOOL_ID, "private-owner")
        )

        assertEquals(
            listOf("private-novel-canary-world-budget:test-hardware-suspended"),
            blocked.reasons,
        )
        assertEquals(1, allocationCalls)
        assertEquals(3, fixture.trials.stats(TOOL_ID).trials)
        assertTrue(budgetRepository.accounts.isEmpty())
        assertEquals(GeneratedToolState.TRIAL, fixture.tools.get(TOOL_ID)?.state)
    }

    private suspend fun fixture(
        budgets: ResourceBudgetCoordinator? = null,
        sharedBudgetsProvider: () -> SharedResourceBudgetGate? = { null },
    ): Fixture {
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
            budgets = budgets,
            sharedBudgetsProvider = sharedBudgetsProvider,
        )
        return Fixture(
            coordinator = coordinator,
            trials = trials,
            lifecycle = lifecycle,
            durable = durable,
            capabilities = capabilities,
            tools = tools,
        )
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

    private data class Fixture(
        val coordinator: PrivateNovelCapabilityActivationCoordinator,
        val trials: GeneratedToolTrialLedger,
        val lifecycle: GeneratedToolLifecycleCoordinator,
        val durable: MemoryBoundedStateRepository,
        val capabilities: CapabilityRegistry,
        val tools: GeneratedToolRegistry,
    )

    private class MemoryArtifactRepository(val artifact: GeneratedToolArtifact) : GeneratedToolArtifactRepository {
        override suspend fun persist(artifact: GeneratedToolArtifact) = error("read-only")
        override suspend fun load(toolId: String): GeneratedToolArtifact? = artifact.takeIf { it.toolId == toolId }
        override suspend fun loadAll(): List<GeneratedToolArtifact> = listOf(artifact)
    }

    private class MemoryResourceBudgetRepository : ResourceBudgetRepository {
        val accounts = linkedMapOf<ResourceBudgetAccountId, ResourceBudgetAccount>()

        override suspend fun load(accountId: ResourceBudgetAccountId): ResourceBudgetRepositoryLoadReport =
            ResourceBudgetRepositoryLoadReport(accounts[accountId])

        override suspend fun create(account: ResourceBudgetAccount): Boolean {
            if (accounts.containsKey(account.id)) return false
            accounts[account.id] = account
            return true
        }

        override suspend fun compareAndSet(
            accountId: ResourceBudgetAccountId,
            expectedRevision: Long,
            updated: ResourceBudgetAccount,
        ): Boolean {
            val current = accounts[accountId] ?: return false
            if (current.revision != expectedRevision) return false
            accounts[accountId] = updated
            return true
        }
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