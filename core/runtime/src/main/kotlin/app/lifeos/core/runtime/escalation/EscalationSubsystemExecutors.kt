package app.lifeos.core.runtime.escalation

import app.lifeos.core.model.health.ProtectionActor
import app.lifeos.core.model.health.ProtectionMode
import app.lifeos.core.model.health.ProtectionReason
import app.lifeos.core.model.health.ProtectionResumePolicy
import app.lifeos.core.model.health.RuntimeProtectionState
import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.runtime.RuntimeFailure
import app.lifeos.core.runtime.capability.CapabilityMatchRequest
import app.lifeos.core.runtime.capability.CapabilityMatchResult
import app.lifeos.core.runtime.capability.CapabilityMatcher
import app.lifeos.core.runtime.capability.CapabilityProviderScore
import app.lifeos.core.runtime.capability.GeneratedToolHotSwapRevertCoordinator
import app.lifeos.core.runtime.capability.GeneratedToolHotSwapRevertResult
import app.lifeos.core.runtime.capability.HotSwapResourceProfile
import app.lifeos.core.runtime.capability.HotSwapTransactionId
import app.lifeos.core.runtime.cognition.CognitiveTrigger
import app.lifeos.core.runtime.cognition.CognitiveTriggerSink
import app.lifeos.core.runtime.cognition.CognitiveTriggerType
import app.lifeos.core.runtime.genesis.GenesisCoordinator
import app.lifeos.core.runtime.genesis.GenesisRequest
import app.lifeos.core.runtime.genesis.GenesisResult
import app.lifeos.core.runtime.health.DurableSelfHealingCoordinator
import app.lifeos.core.runtime.health.DurableSelfHealingResult
import app.lifeos.core.runtime.health.HealthNodeId
import app.lifeos.core.runtime.health.ProtectionCoordinator
import app.lifeos.core.runtime.health.RecoveryPlan
import app.lifeos.core.runtime.health.SelfHealingResourceProfile
import app.lifeos.core.runtime.tasks.RetryPolicy
import app.lifeos.core.runtime.tasks.RetrySchedule
import java.time.Instant

data class RetryEscalationContext(
    val task: LifeTask,
    val failures: List<RuntimeFailure>,
    val scheduledAt: Instant,
) {
    init { require(failures.isNotEmpty()) }
}

fun interface RetryEscalationContextSource {
    suspend fun resolve(request: EscalationExecutionRequest): RetryEscalationContext?
}

/**
 * Must be idempotent for request.executionId. Implementations normally delegate to DurableTaskEngine
 * or another durable scheduler with that identity as its idempotency key.
 */
fun interface EscalationRetryScheduler {
    suspend fun schedule(
        request: EscalationExecutionRequest,
        context: RetryEscalationContext,
        schedule: RetrySchedule,
    ): EscalationExecutionResult
}

class RetryPolicyEscalationExecutor(
    private val policy: RetryPolicy,
    private val contexts: RetryEscalationContextSource,
    private val scheduler: EscalationRetryScheduler,
) : EscalationLevelExecutor {
    override suspend fun execute(request: EscalationExecutionRequest): EscalationExecutionResult {
        requireLevel(request, EscalationLevel.L0_RETRY)
        val context = contexts.resolve(request)
            ?: return EscalationExecutionResult.Blocked("retry-context-unavailable")
        val retry = policy.nextRetry(context.task, context.failures, context.scheduledAt)
            ?: return EscalationExecutionResult.Blocked("retry-policy-exhausted-or-non-retryable")
        return scheduler.schedule(request, context, retry)
    }
}

fun interface ReevaluationTriggerSource {
    suspend fun resolve(request: EscalationExecutionRequest): CognitiveTrigger?
}

class CognitiveReevaluationEscalationExecutor(
    private val triggers: ReevaluationTriggerSource,
    private val sink: CognitiveTriggerSink,
) : EscalationLevelExecutor {
    override suspend fun execute(request: EscalationExecutionRequest): EscalationExecutionResult {
        requireLevel(request, EscalationLevel.L1_REEVALUATE)
        val trigger = triggers.resolve(request)
            ?: return EscalationExecutionResult.Blocked("reevaluation-trigger-unavailable")
        require(trigger.type == CognitiveTriggerType.REEVALUATE) {
            "L1 executor accepts REEVALUATE triggers only"
        }

        val emitted = sink.emit(trigger)
        val durable = emitted || sink.snapshot().any { it.id == trigger.id }
        return if (durable) {
            EscalationExecutionResult.Succeeded(
                detail = "reevaluation-durable:" + trigger.id,
                evidenceRefs = setOf("cognitive-trigger:" + trigger.id),
            )
        } else {
            EscalationExecutionResult.Blocked("reevaluation-not-durable:" + trigger.id)
        }
    }
}

data class SelfHealingEscalationContext(
    val plan: RecoveryPlan,
    val resources: SelfHealingResourceProfile,
    val incidentFingerprint: String? = null,
)

fun interface SelfHealingEscalationContextSource {
    suspend fun resolve(request: EscalationExecutionRequest): SelfHealingEscalationContext?
}

fun interface SelfHealingEscalationAuthority {
    suspend fun recover(
        plan: RecoveryPlan,
        incidentFingerprint: String,
        resources: SelfHealingResourceProfile,
    ): DurableSelfHealingResult

    companion object {
        fun from(coordinator: DurableSelfHealingCoordinator): SelfHealingEscalationAuthority =
            SelfHealingEscalationAuthority { plan, fingerprint, resources ->
                coordinator.recover(plan, fingerprint, resources)
            }
    }
}

class SelfHealingEscalationExecutor(
    private val contexts: SelfHealingEscalationContextSource,
    private val authority: SelfHealingEscalationAuthority,
) : EscalationLevelExecutor {
    override suspend fun execute(request: EscalationExecutionRequest): EscalationExecutionResult {
        requireLevel(request, EscalationLevel.L2_RECOVER_COMPONENT)
        val context = contexts.resolve(request)
            ?: return EscalationExecutionResult.Blocked("self-healing-context-unavailable")
        require(context.plan.nodeId == request.nodeId) {
            "Self-healing plan belongs to another health node"
        }
        val fingerprint = context.incidentFingerprint ?: request.executionId.value
        return when (
            val result = authority.recover(context.plan, fingerprint, context.resources)
        ) {
            is DurableSelfHealingResult.Recovered -> EscalationExecutionResult.Succeeded(
                detail = if (result.recoveredAfterRestart) {
                    "self-healing-recovered-after-restart"
                } else {
                    "self-healing-recovered"
                },
                evidenceRefs = setOf(
                    "self-healing:" + result.incident.incidentId.value,
                    "self-healing-ledger:" + result.incident.ledgerRevision,
                ),
            )
            is DurableSelfHealingResult.Exhausted -> EscalationExecutionResult.Failed(
                detail = "self-healing-exhausted:" + result.incident.state.name.lowercase(),
                evidenceRefs = setOf("self-healing:" + result.incident.incidentId.value),
            )
            is DurableSelfHealingResult.Blocked -> EscalationExecutionResult.Blocked(
                detail = "self-healing-blocked:" + result.reason,
                evidenceRefs = setOf("self-healing:" + result.incident.incidentId.value),
            )
        }
    }
}

data class ProtectionEscalationContext(
    val reasons: List<ProtectionReason>,
    val provenanceTag: String,
    val resumePolicy: ProtectionResumePolicy,
    val affectedNodes: Set<HealthNodeId> = emptySet(),
) {
    init {
        require(reasons.isNotEmpty())
        require(provenanceTag.isNotBlank())
    }
}

fun interface ProtectionEscalationContextSource {
    suspend fun resolve(request: EscalationExecutionRequest): ProtectionEscalationContext?
}

interface ProtectionEscalationAuthority {
    fun snapshot(): RuntimeProtectionState

    suspend fun enterQuarantine(
        nodes: Set<HealthNodeId>,
        reasons: List<ProtectionReason>,
        provenance: String,
        resumePolicy: ProtectionResumePolicy,
    ): RuntimeProtectionState

    suspend fun enterSafeMode(
        reasons: List<ProtectionReason>,
        affectedNodes: Set<HealthNodeId>,
        provenance: String,
        resumePolicy: ProtectionResumePolicy,
    ): RuntimeProtectionState

    companion object {
        fun from(coordinator: ProtectionCoordinator): ProtectionEscalationAuthority =
            object : ProtectionEscalationAuthority {
                override fun snapshot(): RuntimeProtectionState = coordinator.snapshot()

                override suspend fun enterQuarantine(
                    nodes: Set<HealthNodeId>,
                    reasons: List<ProtectionReason>,
                    provenance: String,
                    resumePolicy: ProtectionResumePolicy,
                ): RuntimeProtectionState = coordinator.enterQuarantine(
                    nodes = nodes,
                    reasons = reasons,
                    actor = ProtectionActor.ESCALATION,
                    provenance = provenance,
                    resumePolicy = resumePolicy,
                )

                override suspend fun enterSafeMode(
                    reasons: List<ProtectionReason>,
                    affectedNodes: Set<HealthNodeId>,
                    provenance: String,
                    resumePolicy: ProtectionResumePolicy,
                ): RuntimeProtectionState = coordinator.enterSafeMode(
                    reasons = reasons,
                    affectedNodes = affectedNodes,
                    actor = ProtectionActor.ESCALATION,
                    provenance = provenance,
                    resumePolicy = resumePolicy,
                )
            }
    }
}

class ProtectionQuarantineEscalationExecutor(
    private val contexts: ProtectionEscalationContextSource,
    private val authority: ProtectionEscalationAuthority,
) : EscalationLevelExecutor {
    override suspend fun execute(request: EscalationExecutionRequest): EscalationExecutionResult {
        requireLevel(request, EscalationLevel.L3_QUARANTINE)
        val context = contexts.resolve(request)
            ?: return EscalationExecutionResult.Blocked("quarantine-context-unavailable")
        val provenance = stableProtectionProvenance(request, context.provenanceTag)
        val current = authority.snapshot()
        if (
            current.mode == ProtectionMode.QUARANTINED &&
            current.provenance == provenance &&
            current.affectedNodes.any { it.value == request.nodeId.value }
        ) {
            return protectionSuccess("quarantine-replayed", current)
        }
        val state = authority.enterQuarantine(
            nodes = setOf(request.nodeId),
            reasons = context.reasons,
            provenance = provenance,
            resumePolicy = context.resumePolicy,
        )
        return protectionSuccess("quarantine-entered", state)
    }
}

data class FallbackEscalationContext(
    val matchRequest: CapabilityMatchRequest,
    val expectedProviderId: String? = null,
) {
    init { require(expectedProviderId == null || expectedProviderId.isNotBlank()) }
}

fun interface FallbackEscalationContextSource {
    suspend fun resolve(request: EscalationExecutionRequest): FallbackEscalationContext?
}

fun interface EscalationCapabilityMatcher {
    suspend fun match(request: CapabilityMatchRequest): CapabilityMatchResult

    companion object {
        fun from(matcher: CapabilityMatcher): EscalationCapabilityMatcher =
            EscalationCapabilityMatcher(matcher::match)
    }
}

/**
 * Provider-specific activation remains outside CapabilityMatcher. The sink must use the provider's
 * existing guarded activation/fallback boundary and be idempotent for request.executionId.
 */
fun interface FallbackActivationSink {
    suspend fun activate(
        request: EscalationExecutionRequest,
        selected: CapabilityProviderScore,
    ): EscalationExecutionResult
}

class CapabilityFallbackEscalationExecutor(
    private val contexts: FallbackEscalationContextSource,
    private val matcher: EscalationCapabilityMatcher,
    private val activation: FallbackActivationSink,
) : EscalationLevelExecutor {
    override suspend fun execute(request: EscalationExecutionRequest): EscalationExecutionResult {
        requireLevel(request, EscalationLevel.L4_FALLBACK)
        val context = contexts.resolve(request)
            ?: return EscalationExecutionResult.Blocked("fallback-context-unavailable")
        return when (val match = matcher.match(context.matchRequest)) {
            is CapabilityMatchResult.Selected -> {
                if (
                    context.expectedProviderId != null &&
                    match.selected.provider.providerId != context.expectedProviderId
                ) {
                    EscalationExecutionResult.Blocked(
                        "fallback-selected-unexpected-provider:" + match.selected.provider.providerId
                    )
                } else {
                    activation.activate(request, match.selected)
                }
            }
            is CapabilityMatchResult.Unresolved -> EscalationExecutionResult.Blocked(
                "fallback-unresolved-tie:" +
                    match.tiedCandidates.joinToString(",") { it.provider.providerId }
            )
            is CapabilityMatchResult.Unavailable -> EscalationExecutionResult.Blocked(
                "fallback-provider-unavailable"
            )
        }
    }
}

data class HotSwapRollbackEscalationContext(
    val transactionId: HotSwapTransactionId,
    val resources: HotSwapResourceProfile,
)

fun interface HotSwapRollbackEscalationContextSource {
    suspend fun resolve(request: EscalationExecutionRequest): HotSwapRollbackEscalationContext?
}

fun interface HotSwapRollbackEscalationAuthority {
    suspend fun revert(
        transactionId: HotSwapTransactionId,
        resources: HotSwapResourceProfile,
    ): GeneratedToolHotSwapRevertResult

    companion object {
        fun from(
            coordinator: GeneratedToolHotSwapRevertCoordinator,
        ): HotSwapRollbackEscalationAuthority =
            HotSwapRollbackEscalationAuthority(coordinator::revert)
    }
}

class HotSwapRollbackEscalationExecutor(
    private val contexts: HotSwapRollbackEscalationContextSource,
    private val authority: HotSwapRollbackEscalationAuthority,
) : EscalationLevelExecutor {
    override suspend fun execute(request: EscalationExecutionRequest): EscalationExecutionResult {
        requireLevel(request, EscalationLevel.L5_ROLLBACK)
        val context = contexts.resolve(request)
            ?: return EscalationExecutionResult.Blocked("rollback-context-unavailable")
        return when (val result = authority.revert(context.transactionId, context.resources)) {
            is GeneratedToolHotSwapRevertResult.Reverted -> EscalationExecutionResult.Succeeded(
                "hot-swap-reverted:" + result.transaction.transactionId.value,
                setOf("hot-swap:" + result.transaction.transactionId.value),
            )
            is GeneratedToolHotSwapRevertResult.AlreadyReverted -> EscalationExecutionResult.Succeeded(
                "hot-swap-already-reverted:" + result.transaction.transactionId.value,
                setOf("hot-swap:" + result.transaction.transactionId.value),
            )
            is GeneratedToolHotSwapRevertResult.Blocked -> EscalationExecutionResult.Blocked(
                "hot-swap-revert-blocked:" + result.reason,
                setOf("hot-swap:" + result.transaction.transactionId.value),
            )
        }
    }
}

class ProtectionSafeModeEscalationExecutor(
    private val contexts: ProtectionEscalationContextSource,
    private val authority: ProtectionEscalationAuthority,
) : EscalationLevelExecutor {
    override suspend fun execute(request: EscalationExecutionRequest): EscalationExecutionResult {
        requireLevel(request, EscalationLevel.L6_SAFE_MODE)
        val context = contexts.resolve(request)
            ?: return EscalationExecutionResult.Blocked("safe-mode-context-unavailable")
        val provenance = stableProtectionProvenance(request, context.provenanceTag)
        val current = authority.snapshot()
        if (current.mode == ProtectionMode.SAFE_MODE && current.provenance == provenance) {
            return protectionSuccess("safe-mode-replayed", current)
        }
        val affected = context.affectedNodes.ifEmpty { setOf(request.nodeId) }
        val state = authority.enterSafeMode(
            reasons = context.reasons,
            affectedNodes = affected,
            provenance = provenance,
            resumePolicy = context.resumePolicy,
        )
        return protectionSuccess("safe-mode-entered", state)
    }
}

fun interface GenesisRepairContextSource {
    suspend fun resolve(request: EscalationExecutionRequest): GenesisRequest?
}

fun interface GenesisRepairAuthority {
    suspend fun propose(request: GenesisRequest): GenesisResult

    companion object {
        fun from(coordinator: GenesisCoordinator): GenesisRepairAuthority =
            GenesisRepairAuthority(coordinator::propose)
    }
}

class GenesisRepairEscalationExecutor(
    private val contexts: GenesisRepairContextSource,
    private val authority: GenesisRepairAuthority,
) : EscalationLevelExecutor {
    override suspend fun execute(request: EscalationExecutionRequest): EscalationExecutionResult {
        requireLevel(request, EscalationLevel.L7_REPAIR_PROPOSAL)
        val context = contexts.resolve(request)
            ?: return EscalationExecutionResult.Blocked("genesis-context-unavailable")
        return when (val result = authority.propose(context)) {
            is GenesisResult.Proposed -> {
                require(!result.proposal.handoff.activationAllowed) {
                    "Escalation L7 must never receive activation authority"
                }
                EscalationExecutionResult.Succeeded(
                    detail = "repair-proposal-created:" + result.proposal.id,
                    evidenceRefs = setOf("genesis-proposal:" + result.proposal.id),
                )
            }
            is GenesisResult.NoAction -> EscalationExecutionResult.Succeeded(
                "repair-proposal-not-required:" + result.reason
            )
            is GenesisResult.Blocked -> EscalationExecutionResult.Blocked(
                "repair-proposal-blocked:" + result.reasons.joinToString("|")
            )
        }
    }
}

data class EscalationSubsystemExecutorSet(
    val retry: EscalationLevelExecutor,
    val reevaluate: EscalationLevelExecutor,
    val recover: EscalationLevelExecutor,
    val quarantine: EscalationLevelExecutor,
    val fallback: EscalationLevelExecutor,
    val rollback: EscalationLevelExecutor,
    val safeMode: EscalationLevelExecutor,
    val repairProposal: EscalationLevelExecutor,
) {
    fun registry(): EscalationExecutorRegistry = EscalationExecutorRegistry(
        mapOf(
            EscalationLevel.L0_RETRY to retry,
            EscalationLevel.L1_REEVALUATE to reevaluate,
            EscalationLevel.L2_RECOVER_COMPONENT to recover,
            EscalationLevel.L3_QUARANTINE to quarantine,
            EscalationLevel.L4_FALLBACK to fallback,
            EscalationLevel.L5_ROLLBACK to rollback,
            EscalationLevel.L6_SAFE_MODE to safeMode,
            EscalationLevel.L7_REPAIR_PROPOSAL to repairProposal,
        )
    )
}

private fun requireLevel(
    request: EscalationExecutionRequest,
    expected: EscalationLevel,
) {
    require(request.level == expected) {
        "Escalation executor " + expected.name + " received " + request.level.name
    }
}

private fun stableProtectionProvenance(
    request: EscalationExecutionRequest,
    tag: String,
): String {
    val digest = request.executionId.value.removePrefix(EscalationExecutionId.PREFIX)
    return ("escalation:" + digest + ":" + tag).take(256)
}

private fun protectionSuccess(
    detail: String,
    state: RuntimeProtectionState,
): EscalationExecutionResult.Succeeded = EscalationExecutionResult.Succeeded(
    detail = detail + ":generation-" + state.generation + ":revision-" + state.revision,
    evidenceRefs = setOf(
        "protection-generation:" + state.generation,
        "protection-revision:" + state.revision,
    ),
)
