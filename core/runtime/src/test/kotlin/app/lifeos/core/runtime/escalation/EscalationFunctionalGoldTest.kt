package app.lifeos.core.runtime.escalation

import app.lifeos.core.model.health.ProtectionActor
import app.lifeos.core.model.health.ProtectionMode
import app.lifeos.core.model.health.ProtectionNodeRef
import app.lifeos.core.model.health.ProtectionReason
import app.lifeos.core.model.health.ProtectionReasonCode
import app.lifeos.core.model.health.ProtectionResumePolicy
import app.lifeos.core.model.health.ProtectionStateLoadResult
import app.lifeos.core.model.health.ProtectionStateWriteResult
import app.lifeos.core.model.health.RuntimeProtectionState
import app.lifeos.core.model.health.RuntimeProtectionStateRepository
import app.lifeos.core.runtime.capability.CapabilityContract
import app.lifeos.core.runtime.capability.CapabilityDescriptor
import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.capability.CapabilityGapType
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityMatchRequest
import app.lifeos.core.runtime.capability.CapabilityMatchResult
import app.lifeos.core.runtime.capability.CapabilityProviderProfile
import app.lifeos.core.runtime.capability.CapabilityProviderScore
import app.lifeos.core.runtime.capability.CapabilityRequirement
import app.lifeos.core.runtime.capability.CapabilityScoreComponent
import app.lifeos.core.runtime.capability.CapabilityScoreTerm
import app.lifeos.core.runtime.capability.GapSeverity
import app.lifeos.core.runtime.capability.GeneratedToolHotSwapRevertResult
import app.lifeos.core.runtime.capability.HotSwapResourceProfile
import app.lifeos.core.runtime.capability.HotSwapSnapshot
import app.lifeos.core.runtime.capability.HotSwapState
import app.lifeos.core.runtime.capability.HotSwapTransactionId
import app.lifeos.core.runtime.capability.ProviderState
import app.lifeos.core.runtime.capability.ProviderType
import app.lifeos.core.runtime.capability.TrustLevel
import app.lifeos.core.runtime.genesis.GenesisHandoff
import app.lifeos.core.runtime.genesis.GenesisHandoffTarget
import app.lifeos.core.runtime.genesis.GenesisProposal
import app.lifeos.core.runtime.genesis.GenesisRequest
import app.lifeos.core.runtime.genesis.GenesisResult
import app.lifeos.core.runtime.genesis.GenesisSolutionCandidate
import app.lifeos.core.runtime.genesis.GenesisSolutionKind
import app.lifeos.core.runtime.health.HealthFailureCategory
import app.lifeos.core.runtime.health.HealthGatePurpose
import app.lifeos.core.runtime.health.HealthNodeId
import app.lifeos.core.runtime.health.HealthScope
import app.lifeos.core.runtime.health.ProtectionAdmissionDecision
import app.lifeos.core.runtime.health.ProtectionCoordinator
import app.lifeos.core.runtime.health.ProtectionResumeResult
import app.lifeos.core.runtime.health.ProtectionResumeVerifier
import app.lifeos.core.runtime.health.ProtectionVerificationResult
import app.lifeos.core.runtime.health.QuarantineRegistry
import app.lifeos.core.runtime.resource.ResourceBudgetQuota
import app.lifeos.core.runtime.resource.ResourceBudgetUsage
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * R04 explicit GOLD proof for the ten deterministic escalation scenarios in
 * docs/LIFEOS_MASTER_EXECUTION_PLAN.md. The test exercises policy -> durable ledger ->
 * coordinator and, where safety ownership matters, the real subsystem adapter boundary.
 */
class EscalationFunctionalGoldTest {
    @Test
    fun scenario01_transientStorageTimeoutResolvesAtL0() = runBlocking {
        val seen = mutableListOf<EscalationLevel>()
        val coordinator = coordinator(
            executorFor = { level ->
                EscalationLevelExecutor {
                    seen += level
                    EscalationExecutionResult.Succeeded("retry-scheduled")
                }
            }
        )

        val result = completed(
            coordinator.coordinate(
                trigger(
                    scope = HealthScope.STORAGE_ENGINE,
                    category = HealthFailureCategory.TIMEOUT,
                    recoverable = true,
                    retryBudgetRemaining = true,
                )
            )
        )

        assertEquals(EscalationLevel.L0_RETRY, result.snapshot.level)
        assertEquals(listOf(EscalationLevel.L0_RETRY), seen)
        assertIs<EscalationExecutionResult.Succeeded>(result.execution)
    }

    @Test
    fun scenario02_inconsistentContextTriggersL1WithoutQuarantine() = runBlocking {
        val seen = mutableListOf<EscalationLevel>()
        val coordinator = coordinator(
            executorFor = { level ->
                EscalationLevelExecutor {
                    seen += level
                    EscalationExecutionResult.Succeeded("reevaluated")
                }
            }
        )

        val result = completed(
            coordinator.coordinate(
                trigger(
                    retryBudgetRemaining = false,
                    contextInconsistent = true,
                )
            )
        )

        assertEquals(EscalationLevel.L1_REEVALUATE, result.snapshot.level)
        assertEquals(listOf(EscalationLevel.L1_REEVALUATE), seen)
        assertTrue(EscalationLevel.L3_QUARANTINE !in seen)
    }

    @Test
    fun scenario03_workerHeartbeatFailureTriggersL2AndRecovers() = runBlocking {
        val coordinator = coordinator(
            executorFor = { level ->
                EscalationLevelExecutor {
                    if (level == EscalationLevel.L2_RECOVER_COMPONENT) {
                        EscalationExecutionResult.Succeeded("worker-recovered")
                    } else {
                        EscalationExecutionResult.Blocked("unexpected-level:" + level.name)
                    }
                }
            }
        )

        val result = completed(
            coordinator.coordinate(
                trigger(
                    scope = HealthScope.WORKER,
                    category = HealthFailureCategory.WORKER,
                    recoverable = true,
                    retryBudgetRemaining = false,
                    componentRecoveryAvailable = true,
                )
            )
        )

        assertEquals(EscalationLevel.L2_RECOVER_COMPONENT, result.snapshot.level)
        assertEquals(EscalationState.ACTION_SUCCEEDED, result.snapshot.state)
    }

    @Test
    fun scenario04_repeatedFieldCrashReachesL3AndUnrelatedDomainContinues() = runBlocking {
        val fieldNode = HealthNodeId("field:crashing")
        val otherNode = HealthNodeId("planner:healthy")
        val protection = protectionCoordinator()
        val quarantine = ProtectionQuarantineEscalationExecutor(
            contexts = ProtectionEscalationContextSource {
                ProtectionEscalationContext(
                    reasons = listOf(
                        ProtectionReason(
                            code = ProtectionReasonCode.REPEATED_FAILURE,
                            source = "r04-gold",
                            message = "repeated field crash",
                        )
                    ),
                    provenanceTag = "r04-field-quarantine",
                    resumePolicy = ProtectionResumePolicy.AUTO_AFTER_VERIFICATION,
                )
            },
            authority = ProtectionEscalationAuthority.from(protection),
        )
        val coordinator = coordinator(
            overrides = mapOf(EscalationLevel.L3_QUARANTINE to quarantine)
        )

        val result = completed(
            coordinator.coordinate(
                trigger(
                    nodeId = fieldNode,
                    scope = HealthScope.FIELD,
                    category = HealthFailureCategory.FIELD,
                    consecutiveFailures = 3,
                    retryBudgetRemaining = false,
                )
            )
        )

        assertEquals(EscalationLevel.L3_QUARANTINE, result.snapshot.level)
        assertIs<ProtectionAdmissionDecision.Blocked>(
            protection.admit(fieldNode, HealthGatePurpose.NORMAL)
        )
        assertIs<ProtectionAdmissionDecision.Allowed>(
            protection.admit(otherNode, HealthGatePurpose.NORMAL)
        )
    }

    @Test
    fun scenario05_gpuBackendFailureSwitchesToCpuReferenceAtL4() = runBlocking {
        val selected = providerScore("cpu-reference")
        var activated: String? = null
        val fallback = CapabilityFallbackEscalationExecutor(
            contexts = FallbackEscalationContextSource {
                FallbackEscalationContext(
                    matchRequest = CapabilityMatchRequest(
                        requirement = CapabilityRequirement(
                            capabilityId = CapabilityId("image.rasterize"),
                            requiredOutputs = setOf("image"),
                        ),
                    ),
                    expectedProviderId = "cpu-reference",
                )
            },
            matcher = EscalationCapabilityMatcher {
                CapabilityMatchResult.Selected(
                    request = it,
                    selected = selected,
                    rankedCandidates = listOf(selected),
                    excluded = emptyList(),
                )
            },
            activation = FallbackActivationSink { _, score ->
                activated = score.provider.providerId
                EscalationExecutionResult.Succeeded(
                    "fallback-activated:" + score.provider.providerId
                )
            },
        )
        val coordinator = coordinator(
            overrides = mapOf(EscalationLevel.L4_FALLBACK to fallback)
        )

        val result = completed(
            coordinator.coordinate(
                trigger(
                    scope = HealthScope.BUILD_STUDIO,
                    category = HealthFailureCategory.RESOURCE,
                    consecutiveFailures = 2,
                    retryBudgetRemaining = false,
                    knownGoodFallbackId = "cpu-reference",
                )
            )
        )

        assertEquals(EscalationLevel.L4_FALLBACK, result.snapshot.level)
        assertEquals("cpu-reference", activated)
    }

    @Test
    fun scenario06_newlyPromotedToolRegressionRollsBackAtL5() = runBlocking {
        val capabilityId = CapabilityId("tool.regression")
        val transactionId = HotSwapTransactionId.create(
            capabilityId = capabilityId,
            previousToolId = "tool-stable",
            candidateToolId = "tool-new",
            previousPromotionEvidenceId = "evidence-stable",
            candidatePromotionEvidenceId = "evidence-new",
        )
        val reverted = HotSwapSnapshot(
            transactionId = transactionId,
            capabilityId = capabilityId,
            previousToolId = "tool-stable",
            candidateToolId = "tool-new",
            previousPromotionEvidenceId = "evidence-stable",
            candidatePromotionEvidenceId = "evidence-new",
            state = HotSwapState.REVERTED,
            ledgerRevision = 4L,
            lastRecordedAt = NOW,
        )
        val resources = HotSwapResourceProfile(
            hardQuota = ResourceBudgetQuota(
                elapsedMillis = 1000,
                workUnits = 4,
                memoryBytes = 1024,
                ioBytes = 1024,
                networkBytes = 0,
                candidates = 1,
            ),
            requested = ResourceBudgetUsage(
                elapsedMillis = 100,
                workUnits = 1,
                memoryBytes = 128,
                ioBytes = 128,
                networkBytes = 0,
                candidates = 1,
            ),
        )
        val rollback = HotSwapRollbackEscalationExecutor(
            contexts = HotSwapRollbackEscalationContextSource {
                HotSwapRollbackEscalationContext(transactionId, resources)
            },
            authority = HotSwapRollbackEscalationAuthority { id, _ ->
                require(id == transactionId)
                GeneratedToolHotSwapRevertResult.Reverted(reverted)
            },
        )
        val coordinator = coordinator(
            overrides = mapOf(EscalationLevel.L5_ROLLBACK to rollback)
        )

        val result = completed(
            coordinator.coordinate(
                trigger(
                    recoverable = false,
                    retryBudgetRemaining = false,
                    recentPromotionId = "promotion:new-tool",
                    rollbackAvailable = true,
                )
            )
        )

        assertEquals(EscalationLevel.L5_ROLLBACK, result.snapshot.level)
        assertIs<EscalationExecutionResult.Succeeded>(result.execution)
        assertTrue(result.execution.detail.contains("hot-swap-reverted"))
    }

    @Test
    fun scenario07_corruptProtectionCriticalStoreEntersL6() = runBlocking {
        val protection = protectionCoordinator()
        val safeMode = ProtectionSafeModeEscalationExecutor(
            contexts = ProtectionEscalationContextSource {
                ProtectionEscalationContext(
                    reasons = listOf(
                        ProtectionReason(
                            code = ProtectionReasonCode.INTEGRITY_FAILURE,
                            source = "r04-gold",
                            message = "critical store corruption",
                        )
                    ),
                    provenanceTag = "r04-safe-mode",
                    resumePolicy = ProtectionResumePolicy.USER_AFTER_VERIFICATION,
                    affectedNodes = setOf(HealthNodeId("store:critical")),
                )
            },
            authority = ProtectionEscalationAuthority.from(protection),
        )
        val coordinator = coordinator(
            overrides = mapOf(EscalationLevel.L6_SAFE_MODE to safeMode)
        )

        val result = completed(
            coordinator.coordinate(
                trigger(
                    nodeId = HealthNodeId("store:critical"),
                    scope = HealthScope.STORAGE_ENGINE,
                    category = HealthFailureCategory.DATA_CORRUPTION,
                    recoverable = false,
                    retryBudgetRemaining = false,
                    protectionCritical = true,
                )
            )
        )

        assertEquals(EscalationLevel.L6_SAFE_MODE, result.snapshot.level)
        assertEquals(ProtectionMode.SAFE_MODE, protection.snapshot().mode)
        assertIs<ProtectionAdmissionDecision.Blocked>(
            protection.admit(HealthNodeId("unrelated"), HealthGatePurpose.NORMAL)
        )
    }

    @Test
    fun scenario08_repeatedMissingCapabilityCreatesL7ProposalWithoutActivation() = runBlocking {
        val requirement = CapabilityRequirement(
            capabilityId = CapabilityId("capability.missing"),
            severity = GapSeverity.BLOCKING,
            requiredOutputs = setOf("text"),
        )
        val genesisRequest = GenesisRequest(requirement = requirement)
        val gap = CapabilityGap(requirement, CapabilityGapType.CAPABILITY_MISSING)
        val candidate = GenesisSolutionCandidate(
            kind = GenesisSolutionKind.CODE_TOOL,
            referenceId = "repair-tool",
            reliability = 0.0,
            expectedCost = 1.0,
            rationale = "demonstrated-missing-capability",
            requiresExplicitApproval = true,
        )
        val handoff = GenesisHandoff(
            proposalId = "r04-proposal",
            target = GenesisHandoffTarget.TOOL_WORKSHOP,
            referenceId = "repair-tool",
            payloadFingerprint = "repair-payload",
            requiresExplicitApproval = true,
        )
        val proposal = GenesisProposal(
            id = "r04-proposal",
            requestId = genesisRequest.id,
            gap = gap,
            selected = candidate,
            consideredKinds = listOf(GenesisSolutionKind.CODE_TOOL),
            evidenceRefs = setOf("l2-l6-evidence"),
            handoff = handoff,
        )
        val genesis = GenesisRepairEscalationExecutor(
            contexts = GenesisRepairContextSource { genesisRequest },
            authority = GenesisRepairAuthority { GenesisResult.Proposed(proposal) },
        )
        val coordinator = coordinator(
            overrides = mapOf(EscalationLevel.L7_REPAIR_PROPOSAL to genesis)
        )

        val result = completed(
            coordinator.coordinate(
                trigger(
                    recoverable = false,
                    retryBudgetRemaining = false,
                    missingCapabilityId = requirement.capabilityId.value,
                    repairProposalEligible = true,
                    priorLevels = listOf(
                        EscalationLevel.L2_RECOVER_COMPONENT,
                        EscalationLevel.L3_QUARANTINE,
                    ),
                )
            )
        )

        assertEquals(EscalationLevel.L7_REPAIR_PROPOSAL, result.snapshot.level)
        assertTrue(!proposal.handoff.activationAllowed)
        assertTrue(result.execution.evidenceRefs.contains("genesis-proposal:r04-proposal"))
    }

    @Test
    fun scenario09_lateSuccessCannotClearNewerQuarantineGeneration() = runBlocking {
        val repository = MemoryProtectionRepository()
        lateinit var coordinator: ProtectionCoordinator
        coordinator = ProtectionCoordinator(
            repository = repository,
            quarantineRegistry = QuarantineRegistry(),
            verifier = ProtectionResumeVerifier { state ->
                val newer = state.nextRevision(
                    mode = ProtectionMode.QUARANTINED,
                    reasons = state.reasons,
                    affectedNodes = state.affectedNodes,
                    enteredAt = NOW.plusSeconds(1),
                    lastVerifiedAt = null,
                    actor = ProtectionActor.ESCALATION,
                    provenance = "newer-quarantine-generation",
                    resumePolicy = ProtectionResumePolicy.AUTO_AFTER_VERIFICATION,
                    advanceGeneration = true,
                )
                repository.force(newer)
                ProtectionVerificationResult.Verified("late-old-generation-success")
            },
            now = { NOW },
        )
        val node = HealthNodeId("field:generation")
        val initial = coordinator.enterQuarantine(
            nodes = setOf(node),
            reasons = listOf(
                ProtectionReason(
                    code = ProtectionReasonCode.REPEATED_FAILURE,
                    source = "r04-gold",
                    message = "generation one quarantine",
                )
            ),
            actor = ProtectionActor.ESCALATION,
            provenance = "generation-one",
        )

        val result = coordinator.requestResume(
            actor = ProtectionActor.RECOVERY,
            provenance = "late-success",
        )

        assertIs<ProtectionResumeResult.VerificationRejected>(result)
        assertEquals(initial.generation + 1L, coordinator.snapshot().generation)
        assertEquals(ProtectionMode.QUARANTINED, coordinator.snapshot().mode)
        assertEquals("newer-quarantine-generation", coordinator.snapshot().provenance)
    }

    @Test
    fun scenario10_processRestartRehydratesActiveEscalationAndProtection() = runBlocking {
        val escalationRepository = MemoryEscalationRepository()
        val trigger = trigger(
            nodeId = HealthNodeId("worker:restart"),
            scope = HealthScope.WORKER,
            category = HealthFailureCategory.WORKER,
            retryBudgetRemaining = false,
        )
        val firstCoordinator = coordinator(
            repository = escalationRepository,
            executorFor = { level ->
                EscalationLevelExecutor {
                    if (level == EscalationLevel.L2_RECOVER_COMPONENT) {
                        throw CancellationException("simulated-process-death")
                    }
                    EscalationExecutionResult.Succeeded("unexpected")
                }
            },
        )
        assertIs<CancellationException>(
            runCatching { firstCoordinator.coordinate(trigger) }.exceptionOrNull()
        )
        assertEquals(
            EscalationState.ACTION_IN_FLIGHT,
            EscalationLedger(escalationRepository).snapshot(trigger.id)?.state,
        )

        val secondCoordinator = coordinator(
            repository = escalationRepository,
            executorFor = { level ->
                EscalationLevelExecutor {
                    require(level == EscalationLevel.L2_RECOVER_COMPONENT)
                    EscalationExecutionResult.Succeeded("recovered-after-restart")
                }
            },
        )
        val resumed = completed(secondCoordinator.resumeActive(trigger.nodeId).single())
        assertTrue(resumed.resumed)
        assertEquals(EscalationState.ACTION_SUCCEEDED, resumed.snapshot.state)

        val protectionRepository = MemoryProtectionRepository()
        val firstProtection = protectionCoordinator(protectionRepository)
        val protected = firstProtection.enterQuarantine(
            nodes = setOf(trigger.nodeId),
            reasons = listOf(
                ProtectionReason(
                    code = ProtectionReasonCode.REPEATED_FAILURE,
                    source = "r04-gold",
                    message = "persist across restart",
                )
            ),
            actor = ProtectionActor.ESCALATION,
            provenance = "restart-proof",
        )
        val secondProtection = protectionCoordinator(protectionRepository)
        val rehydrated = secondProtection.rehydrate()
        assertEquals(protected, rehydrated)
        assertEquals(ProtectionMode.QUARANTINED, secondProtection.snapshot().mode)
    }

    private fun coordinator(
        repository: EscalationRepository = MemoryEscalationRepository(),
        overrides: Map<EscalationLevel, EscalationLevelExecutor> = emptyMap(),
        executorFor: ((EscalationLevel) -> EscalationLevelExecutor)? = null,
    ): EscalationCoordinator {
        val executors = EscalationLevel.entries.associateWith { level ->
            overrides[level]
                ?: executorFor?.invoke(level)
                ?: EscalationLevelExecutor {
                    EscalationExecutionResult.Succeeded("executed:" + level.name)
                }
        }
        return EscalationCoordinator(
            policy = EscalationPolicy(),
            ledger = EscalationLedger(repository) { NOW },
            executors = EscalationExecutorRegistry(executors),
        )
    }

    private fun completed(
        result: EscalationCoordinationResult,
    ): EscalationCoordinationResult.Completed =
        assertIs<EscalationCoordinationResult.Completed>(result)

    private fun trigger(
        nodeId: HealthNodeId = HealthNodeId("runtime:r04"),
        scope: HealthScope = HealthScope.RUNTIME,
        category: HealthFailureCategory = HealthFailureCategory.TRANSIENT,
        recoverable: Boolean = true,
        consecutiveFailures: Int = 1,
        retryBudgetRemaining: Boolean = false,
        componentRecoveryAvailable: Boolean = true,
        contextInconsistent: Boolean = false,
        knownGoodFallbackId: String? = null,
        recentPromotionId: String? = null,
        rollbackAvailable: Boolean = false,
        protectionCritical: Boolean = false,
        missingCapabilityId: String? = null,
        repairProposalEligible: Boolean = false,
        priorLevels: List<EscalationLevel> = emptyList(),
    ) = EscalationTrigger(
        nodeId = nodeId,
        scope = scope,
        category = category,
        recoverable = recoverable,
        consecutiveFailures = consecutiveFailures,
        retryBudgetRemaining = retryBudgetRemaining,
        componentRecoveryAvailable = componentRecoveryAvailable,
        contextInconsistent = contextInconsistent,
        knownGoodFallbackId = knownGoodFallbackId,
        recentPromotionId = recentPromotionId,
        rollbackAvailable = rollbackAvailable,
        protectionCritical = protectionCritical,
        missingCapabilityId = missingCapabilityId,
        repairProposalEligible = repairProposalEligible,
        priorLevels = priorLevels,
        evidenceRefs = setOf("r04-gold-evidence"),
        observedAt = NOW,
    )

    private fun providerScore(providerId: String): CapabilityProviderScore {
        val provider = CapabilityDescriptor(
            capabilityId = CapabilityId("image.rasterize"),
            providerId = providerId,
            providerType = ProviderType.INTERNAL_TOOL,
            contract = CapabilityContract(outputs = setOf("image")),
            state = ProviderState.ACTIVE,
            trustLevel = TrustLevel.HIGH,
            reliability = 1.0,
            cost = 0.0,
        )
        return CapabilityProviderScore(
            provider = provider,
            profile = CapabilityProviderProfile(),
            terms = CapabilityScoreComponent.entries.map { component ->
                CapabilityScoreTerm(
                    component = component,
                    rawScore = 1.0,
                    weight = 1.0,
                    contribution = 0.2,
                )
            },
            totalScore = 1.0,
        )
    }

    private fun protectionCoordinator(
        repository: MemoryProtectionRepository = MemoryProtectionRepository(),
    ): ProtectionCoordinator = ProtectionCoordinator(
        repository = repository,
        quarantineRegistry = QuarantineRegistry(),
        verifier = ProtectionResumeVerifier {
            ProtectionVerificationResult.Verified("verified")
        },
        now = { NOW },
    )

    private class MemoryEscalationRepository : EscalationRepository {
        private val records = mutableListOf<EscalationRecord>()

        override suspend fun loadReport(): EscalationRepositoryLoadReport =
            EscalationRepositoryLoadReport(records.toList())

        override suspend fun append(
            expectedRevision: Long,
            record: EscalationRecord,
        ): Boolean {
            val current = records.lastOrNull()?.revision ?: 0L
            if (current != expectedRevision) return false
            records += record
            return true
        }
    }

    private class MemoryProtectionRepository : RuntimeProtectionStateRepository {
        private var state: RuntimeProtectionState? = null

        override suspend fun load(): ProtectionStateLoadResult =
            state?.let(ProtectionStateLoadResult::Loaded) ?: ProtectionStateLoadResult.Missing

        override suspend fun compareAndSet(
            expectedRevision: Long?,
            next: RuntimeProtectionState,
        ): ProtectionStateWriteResult {
            val actual = state?.revision
            if (actual != expectedRevision) {
                return ProtectionStateWriteResult.Conflict(actual)
            }
            state = next
            return ProtectionStateWriteResult.Saved(next)
        }

        fun force(next: RuntimeProtectionState) {
            state = next
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-19T02:00:00Z")
    }
}
