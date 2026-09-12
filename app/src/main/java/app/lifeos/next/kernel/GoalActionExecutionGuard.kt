package app.lifeos.next.kernel

import app.lifeos.core.language.IntentType
import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.policy.OwnerEffectRequest
import app.lifeos.core.runtime.policy.OwnerEffectType
import app.lifeos.core.runtime.policy.OwnerPolicyAssessment
import app.lifeos.core.runtime.policy.OwnerPolicyDecisionId
import app.lifeos.core.runtime.policy.OwnerPolicyEffectGate
import app.lifeos.core.runtime.policy.OwnerPolicyLedger
import app.lifeos.core.runtime.resource.HardwareWorkPriority
import app.lifeos.core.runtime.resource.ResourceBudgetAccountId
import app.lifeos.core.runtime.resource.ResourceBudgetCoordinator
import app.lifeos.core.runtime.resource.ResourceBudgetDemand
import app.lifeos.core.runtime.resource.ResourceBudgetDomain
import app.lifeos.core.runtime.resource.ResourceBudgetQuota
import app.lifeos.core.runtime.resource.ResourceBudgetReservation
import app.lifeos.core.runtime.resource.ResourceBudgetReservationResult
import app.lifeos.core.runtime.resource.ResourceBudgetReservationState
import app.lifeos.core.runtime.resource.ResourceBudgetUsage
import app.lifeos.core.runtime.resource.SharedResourceBudgetDecision
import app.lifeos.core.runtime.resource.SharedResourceBudgetGate
import app.lifeos.core.runtime.trace.GoalDecisionTraceRecorder
import java.time.Instant

sealed interface GoalActionExecutionPermit {
    data object Unmetered : GoalActionExecutionPermit

    data class Reserved(
        val accountId: ResourceBudgetAccountId,
        val reservation: ResourceBudgetReservation,
        val ownerPolicyRevision: Long?,
        val ownerPolicyDecisionId: OwnerPolicyDecisionId? = null,
        val worldSnapshotId: String? = null,
        val traceBinding: GoalExecutionTraceBinding? = null,
    ) : GoalActionExecutionPermit

    data class Blocked(val reason: String) : GoalActionExecutionPermit {
        init { require(reason.isNotBlank()) }
    }
}

data class GoalExecutionTraceBinding(
    val goalPhotonId: PhotonId,
    val goalPhotonRevision: Long,
    val recordedAt: Instant,
) {
    init { require(goalPhotonRevision > 0L) }
}

interface GoalActionExecutionGuard {
    suspend fun prepare(context: GoalActionContext): GoalActionExecutionPermit
    suspend fun settle(permit: GoalActionExecutionPermit, result: GoalActionDispatchResult)
}

object GoalExecutionRuntimeRegistry {
    @Volatile
    private var installed: GoalActionExecutionGuard = PassThroughGoalActionExecutionGuard

    fun install(guard: GoalActionExecutionGuard) {
        installed = guard
    }

    fun current(): GoalActionExecutionGuard = installed
}

object PassThroughGoalActionExecutionGuard : GoalActionExecutionGuard {
    override suspend fun prepare(context: GoalActionContext): GoalActionExecutionPermit =
        GoalActionExecutionPermit.Unmetered

    override suspend fun settle(
        permit: GoalActionExecutionPermit,
        result: GoalActionDispatchResult,
    ) = Unit
}

/**
 * Shared private-APK execution gate. Every currently executable goal action is first routed through
 * V16 World Formula budget distribution when the shared broker is installed, then durably reserved.
 * Host-facing reminder/share effects additionally pass the revocable V14 owner ledger immediately
 * before execution. V15 observes the already-authoritative V14/V16 decisions and cannot alter them.
 * All current actions reserve zero network bytes.
 */
class PrivateGoalActionExecutionGuard(
    private val ownerPolicy: OwnerPolicyLedger,
    private val budgets: ResourceBudgetCoordinator,
    private val hardware: HardwareExecutionBudgetGate,
    private val sharedBudgets: SharedResourceBudgetGate? = null,
    private val traces: GoalDecisionTraceRecorder? = null,
    private val ownerPolicyGate: OwnerPolicyEffectGate = OwnerPolicyEffectGate(ownerPolicy),
) : GoalActionExecutionGuard {
    override suspend fun prepare(context: GoalActionContext): GoalActionExecutionPermit {
        val profile = profile(context.goal.intent) ?: return GoalActionExecutionPermit.Unmetered
        val traceBinding = traceBinding(context)
        val policy = policyRequest(context)
        val firstPolicyAssessment = if (policy != null) {
            PrivateOwnerPolicyBaseline.ensure(ownerPolicy)
            ownerPolicyGate.assessLive(policy).also { assessment ->
                traceOwnerPolicy(traceBinding, assessment)
            }
        } else null
        if (firstPolicyAssessment != null && !firstPolicyAssessment.allowed) {
            return GoalActionExecutionPermit.Blocked(
                "owner-policy:${policyBlockReason(firstPolicyAssessment)}",
            )
        }
        val policyRevision = firstPolicyAssessment?.policyRevision
        var ownerPolicyDecisionId = firstPolicyAssessment?.decisionId
        var worldSnapshotId: String? = null

        val reservationUsage = sharedBudgets?.let { broker ->
            val demand = ResourceBudgetDemand(
                domain = profile.domain,
                requested = profile.requested,
                goalRelevance = 1.0,
                priority = profile.priority.asWorldPriority(),
                expectedUtility = profile.expectedUtility,
                confidence = 1.0,
            )
            when (val decision = broker.allocate(profile.hardQuota, listOf(demand))) {
                is SharedResourceBudgetDecision.Blocked -> {
                    traceResourceBlock(
                        traceBinding,
                        source = "world-formula:${profile.domain.name}:${context.goalPhotonId.value}",
                        reason = decision.reason,
                    )
                    return GoalActionExecutionPermit.Blocked(decision.reason)
                }
                is SharedResourceBudgetDecision.Ready -> {
                    val allocation = requireNotNull(decision.allocation.allocation(profile.domain)) {
                        "World Formula budget allocation omitted requested goal domain"
                    }
                    worldSnapshotId = decision.allocation.worldSnapshotId
                    traces?.recordResourceAllocation(
                        goalPhotonId = traceBinding.goalPhotonId,
                        goalPhotonRevision = traceBinding.goalPhotonRevision,
                        recordedAt = traceBinding.recordedAt,
                        domain = profile.domain,
                        worldSnapshotId = decision.allocation.worldSnapshotId,
                        demandFingerprint = allocation.demandFingerprint,
                    )
                    if (!profile.requested.isWithin(allocation.allocated)) {
                        traceResourceBlock(
                            traceBinding,
                            source = decision.allocation.worldSnapshotId,
                            reason = "requested-work-exceeds-world-formula-allocation",
                        )
                        return GoalActionExecutionPermit.Blocked(
                            "requested-work-exceeds-world-formula-allocation",
                        )
                    }
                    allocation.allocated
                }
            }
        } ?: run {
            val hardwareDecision = hardware.plan(
                hardQuota = profile.hardQuota,
                requested = profile.requested,
                priority = profile.priority,
            )
            val ready = hardwareDecision as? HardwareExecutionBudgetDecision.Ready
                ?: run {
                    val reason = (hardwareDecision as HardwareExecutionBudgetDecision.Blocked).reason
                    traceResourceBlock(
                        traceBinding,
                        source = "hardware-budget:${profile.domain.name}:${context.goalPhotonId.value}",
                        reason = reason,
                    )
                    return GoalActionExecutionPermit.Blocked(reason)
                }
            if (!ready.plan.requestedFits) {
                traceResourceBlock(
                    traceBinding,
                    source = "hardware-budget:${profile.domain.name}:${context.goalPhotonId.value}",
                    reason = "requested-work-exceeds-current-hardware-envelope",
                )
                return GoalActionExecutionPermit.Blocked(
                    "requested-work-exceeds-current-hardware-envelope",
                )
            }
            ready.plan.recommendedReservation
        }

        val accountId = ResourceBudgetAccountId("goal-action:${context.goalPhotonId.value}")
        budgets.createAccount(accountId, profile.hardQuota)
        val idempotencyKey = buildString {
            append("goal-action:")
            append(context.goalPhotonId.value)
            append(':')
            append(context.goal.intent.name)
            append(":policy-")
            append(policyRevision?.toString() ?: "none")
        }
        val reservation = when (
            val reserved = budgets.reserve(
                accountId = accountId,
                idempotencyKey = idempotencyKey,
                usage = reservationUsage,
            )
        ) {
            is ResourceBudgetReservationResult.Denied -> {
                traceResourceBlock(traceBinding, accountId.value, reserved.reason)
                return GoalActionExecutionPermit.Blocked(reserved.reason)
            }
            is ResourceBudgetReservationResult.Reserved -> reserved.reservation.also { reservation ->
                traceReservation(traceBinding, reservation)
            }
            is ResourceBudgetReservationResult.Existing -> {
                traceReservation(traceBinding, reserved.reservation)
                when (reserved.reservation.state) {
                    ResourceBudgetReservationState.RESERVED -> reserved.reservation
                    ResourceBudgetReservationState.COMMITTED -> return GoalActionExecutionPermit.Blocked(
                        "goal-action-already-committed",
                    )
                    ResourceBudgetReservationState.RELEASED -> return GoalActionExecutionPermit.Blocked(
                        "goal-action-reservation-released",
                    )
                }
            }
        }

        if (policy != null) {
            val finalAssessment = ownerPolicyGate.assessLive(
                policy.copy(
                    budgetAccountId = accountId,
                    budgetReservationId = reservation.id,
                )
            )
            ownerPolicyDecisionId = finalAssessment.decisionId
            traceOwnerPolicy(traceBinding, finalAssessment)
            if (!finalAssessment.allowed) {
                val released = budgets.release(accountId, reservation.id)
                traceReservation(traceBinding, released)
                return GoalActionExecutionPermit.Blocked(
                    "owner-policy-recheck:${policyBlockReason(finalAssessment)}",
                )
            }
        }
        return GoalActionExecutionPermit.Reserved(
            accountId = accountId,
            reservation = reservation,
            ownerPolicyRevision = policyRevision,
            ownerPolicyDecisionId = ownerPolicyDecisionId,
            worldSnapshotId = worldSnapshotId,
            traceBinding = traceBinding,
        )
    }

    override suspend fun settle(
        permit: GoalActionExecutionPermit,
        result: GoalActionDispatchResult,
    ) {
        val reserved = permit as? GoalActionExecutionPermit.Reserved ?: return
        val actual = successfulUsage(result, reserved.reservation) ?: return
        val settled = budgets.commit(
            accountId = reserved.accountId,
            reservationId = reserved.reservation.id,
            actualUsage = actual,
        )
        reserved.traceBinding?.let { binding -> traceReservation(binding, settled) }
    }

    private suspend fun traceOwnerPolicy(
        binding: GoalExecutionTraceBinding,
        assessment: OwnerPolicyAssessment,
    ) {
        traces?.recordOwnerPolicy(
            goalPhotonId = binding.goalPhotonId,
            goalPhotonRevision = binding.goalPhotonRevision,
            recordedAt = binding.recordedAt,
            assessment = assessment,
        )
    }

    private suspend fun traceResourceBlock(
        binding: GoalExecutionTraceBinding,
        source: String,
        reason: String,
    ) {
        traces?.recordResourceBlock(
            goalPhotonId = binding.goalPhotonId,
            goalPhotonRevision = binding.goalPhotonRevision,
            recordedAt = binding.recordedAt,
            source = source,
            reason = reason,
        )
    }

    private suspend fun traceReservation(
        binding: GoalExecutionTraceBinding,
        reservation: ResourceBudgetReservation,
    ) {
        traces?.recordResourceReservation(
            goalPhotonId = binding.goalPhotonId,
            goalPhotonRevision = binding.goalPhotonRevision,
            recordedAt = binding.recordedAt,
            reservation = reservation,
        )
    }

    private fun traceBinding(context: GoalActionContext): GoalExecutionTraceBinding =
        GoalExecutionTraceBinding(
            goalPhotonId = context.goalPhotonId,
            goalPhotonRevision = context.goalPhotonRevision,
            recordedAt = context.sourcePhoton.provenance.createdAt,
        )

    private fun policyBlockReason(assessment: OwnerPolicyAssessment): String =
        assessment.reasons.joinToString(",").ifBlank {
            assessment.reasonCodes.joinToString(",") { it.name.lowercase().replace('_', '-') }
        }

    private fun successfulUsage(
        result: GoalActionDispatchResult,
        reservation: ResourceBudgetReservation,
    ): ResourceBudgetUsage? = when {
        result.imageGeneration is ImageGenerationResult.Generated -> reservation.reserved
        result.localImageTransform is LocalImageTransformExecutionResult.Transformed -> reservation.reserved
        result.localKnowledge is LocalKnowledgeExecutionResult.Produced -> reservation.reserved
        result.localDeepSearch is LocalDeepSearchExecutionResult.Produced -> {
            val produced = result.localDeepSearch
            ResourceBudgetUsage(workUnits = produced.workUnitsUsed.toLong()).also { usage ->
                require(usage.isWithin(reservation.reserved)) {
                    "DeepSearch reported usage above durable reservation"
                }
            }
        }
        result.localSchedule is LocalScheduleExecutionResult.Scheduled -> reservation.reserved
        result.localCommunication is LocalCommunicationExecutionResult.Prepared -> reservation.reserved
        else -> null
    }

    private fun policyRequest(context: GoalActionContext): OwnerEffectRequest? = when (context.goal.intent) {
        IntentType.SCHEDULE -> OwnerEffectRequest(
            actorId = PrivateOwnerPolicyBaseline.ownerActorId,
            effect = OwnerEffectType.REMINDER,
            resource = REMINDER_RESOURCE,
            scope = PrivateOwnerPolicyBaseline.GOAL_SCOPE,
            capabilityId = context.routing.plan.requirements.firstOrNull()?.capabilityId,
        )
        IntentType.COMMUNICATE -> OwnerEffectRequest(
            actorId = PrivateOwnerPolicyBaseline.ownerActorId,
            effect = OwnerEffectType.COMMUNICATION,
            resource = COMMUNICATION_RESOURCE,
            scope = PrivateOwnerPolicyBaseline.GOAL_SCOPE,
            capabilityId = context.routing.plan.requirements.firstOrNull()?.capabilityId,
        )
        else -> null
    }

    private fun profile(intent: IntentType): GoalActionResourceProfile? = when (intent) {
        IntentType.QUERY,
        IntentType.STORE_OR_REMEMBER -> GoalActionResourceProfile(
            hardQuota = quota(5_000, 24, 64, 8, 0, 4),
            requested = usage(3_000, 8, 24, 2, 0, 2),
            domain = ResourceBudgetDomain.GOAL_EXECUTION,
            expectedUtility = 0.85,
        )
        IntentType.SEARCH -> GoalActionResourceProfile(
            hardQuota = quota(6_000, 24, 96, 12, 0, 8),
            requested = usage(4_000, 16, 48, 4, 0, 6),
            domain = ResourceBudgetDomain.DEEP_SEARCH,
            expectedUtility = 0.90,
        )
        IntentType.CREATE_IMAGE -> GoalActionResourceProfile(
            hardQuota = quota(30_000, 256, 512, 160, 0, 8),
            requested = usage(20_000, 160, 256, 96, 0, 4),
            priority = HardwareWorkPriority.HIGH,
            domain = ResourceBudgetDomain.GOAL_EXECUTION,
            expectedUtility = 0.90,
        )
        IntentType.TRANSFORM_IMAGE -> GoalActionResourceProfile(
            hardQuota = quota(20_000, 160, 384, 160, 0, 4),
            requested = usage(12_000, 96, 192, 96, 0, 2),
            priority = HardwareWorkPriority.HIGH,
            domain = ResourceBudgetDomain.GOAL_EXECUTION,
            expectedUtility = 0.90,
        )
        IntentType.SCHEDULE,
        IntentType.COMMUNICATE -> GoalActionResourceProfile(
            hardQuota = quota(3_000, 8, 32, 4, 0, 2),
            requested = usage(1_500, 4, 16, 1, 0, 1),
            priority = HardwareWorkPriority.HIGH,
            domain = ResourceBudgetDomain.GOAL_EXECUTION,
            expectedUtility = 0.95,
        )
        else -> null
    }

    private data class GoalActionResourceProfile(
        val hardQuota: ResourceBudgetQuota,
        val requested: ResourceBudgetUsage,
        val priority: HardwareWorkPriority = HardwareWorkPriority.NORMAL,
        val domain: ResourceBudgetDomain,
        val expectedUtility: Double,
    )

    private fun HardwareWorkPriority.asWorldPriority(): Double = when (this) {
        HardwareWorkPriority.LOW -> 0.25
        HardwareWorkPriority.NORMAL -> 0.55
        HardwareWorkPriority.HIGH -> 0.80
        HardwareWorkPriority.CRITICAL -> 1.00
    }

    private fun quota(
        elapsedMillis: Long,
        workUnits: Long,
        memoryMiB: Long,
        ioMiB: Long,
        networkMiB: Long,
        candidates: Long,
    ) = ResourceBudgetQuota(
        elapsedMillis = elapsedMillis,
        workUnits = workUnits,
        memoryBytes = memoryMiB * MIB,
        ioBytes = ioMiB * MIB,
        networkBytes = networkMiB * MIB,
        candidates = candidates,
    )

    private fun usage(
        elapsedMillis: Long,
        workUnits: Long,
        memoryMiB: Long,
        ioMiB: Long,
        networkMiB: Long,
        candidates: Long,
    ) = ResourceBudgetUsage(
        elapsedMillis = elapsedMillis,
        workUnits = workUnits,
        memoryBytes = memoryMiB * MIB,
        ioBytes = ioMiB * MIB,
        networkBytes = networkMiB * MIB,
        candidates = candidates,
    )

    private companion object {
        const val MIB = 1024L * 1024L
        const val REMINDER_RESOURCE = "goal://local-reminder"
        const val COMMUNICATION_RESOURCE = "goal://local-share-preparation"
    }
}
