package app.lifeos.next.kernel

import app.lifeos.core.language.IntentType
import app.lifeos.core.runtime.policy.OwnerActorId
import app.lifeos.core.runtime.policy.OwnerEffectRequest
import app.lifeos.core.runtime.policy.OwnerEffectType
import app.lifeos.core.runtime.policy.OwnerPolicyDecision
import app.lifeos.core.runtime.policy.OwnerPolicyGrant
import app.lifeos.core.runtime.policy.OwnerPolicyLedger
import app.lifeos.core.runtime.policy.OwnerResourceSelector
import app.lifeos.core.runtime.policy.OwnerResourceSelectorType
import app.lifeos.core.runtime.resource.HardwareWorkPriority
import app.lifeos.core.runtime.resource.ResourceBudgetAccountId
import app.lifeos.core.runtime.resource.ResourceBudgetCoordinator
import app.lifeos.core.runtime.resource.ResourceBudgetQuota
import app.lifeos.core.runtime.resource.ResourceBudgetReservation
import app.lifeos.core.runtime.resource.ResourceBudgetReservationResult
import app.lifeos.core.runtime.resource.ResourceBudgetReservationState
import app.lifeos.core.runtime.resource.ResourceBudgetUsage
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface GoalActionExecutionPermit {
    data object Unmetered : GoalActionExecutionPermit

    data class Reserved(
        val accountId: ResourceBudgetAccountId,
        val reservation: ResourceBudgetReservation,
        val ownerPolicyRevision: Long?,
    ) : GoalActionExecutionPermit

    data class Blocked(val reason: String) : GoalActionExecutionPermit {
        init { require(reason.isNotBlank()) }
    }
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
 * Shared private-APK execution gate. Every currently executable goal action is hardware-checked and
 * durably reserved before work. Host-facing reminder/share effects additionally pass the revocable
 * V14 owner ledger immediately before execution. All current actions reserve zero network bytes.
 */
class PrivateGoalActionExecutionGuard(
    private val ownerPolicy: OwnerPolicyLedger,
    private val budgets: ResourceBudgetCoordinator,
    private val hardware: HardwareExecutionBudgetGate,
) : GoalActionExecutionGuard {
    private val bootstrapMutex = Mutex()

    override suspend fun prepare(context: GoalActionContext): GoalActionExecutionPermit {
        val profile = profile(context.goal.intent) ?: return GoalActionExecutionPermit.Unmetered
        val policy = policyRequest(context)
        val firstPolicyDecision = if (policy != null) {
            ensurePrivateOwnerBaseline()
            ownerPolicy.evaluate(policy)
        } else null
        if (firstPolicyDecision is OwnerPolicyDecision.Blocked) {
            return GoalActionExecutionPermit.Blocked(
                "owner-policy:${firstPolicyDecision.reasons.joinToString(",")}",
            )
        }
        val policyRevision = firstPolicyDecision?.policyRevision

        val hardwareDecision = hardware.plan(
            hardQuota = profile.hardQuota,
            requested = profile.requested,
            priority = profile.priority,
        )
        val ready = hardwareDecision as? HardwareExecutionBudgetDecision.Ready
            ?: return GoalActionExecutionPermit.Blocked(
                (hardwareDecision as HardwareExecutionBudgetDecision.Blocked).reason,
            )
        if (!ready.plan.requestedFits) {
            return GoalActionExecutionPermit.Blocked(
                "requested-work-exceeds-current-hardware-envelope",
            )
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
                usage = ready.plan.recommendedReservation,
            )
        ) {
            is ResourceBudgetReservationResult.Denied -> return GoalActionExecutionPermit.Blocked(reserved.reason)
            is ResourceBudgetReservationResult.Reserved -> reserved.reservation
            is ResourceBudgetReservationResult.Existing -> {
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
            val finalPolicy = ownerPolicy.evaluate(
                policy.copy(
                    budgetAccountId = accountId,
                    budgetReservationId = reservation.id,
                )
            )
            if (finalPolicy is OwnerPolicyDecision.Blocked) {
                budgets.release(accountId, reservation.id)
                return GoalActionExecutionPermit.Blocked(
                    "owner-policy-recheck:${finalPolicy.reasons.joinToString(",")}",
                )
            }
        }
        return GoalActionExecutionPermit.Reserved(
            accountId = accountId,
            reservation = reservation,
            ownerPolicyRevision = policyRevision,
        )
    }

    override suspend fun settle(
        permit: GoalActionExecutionPermit,
        result: GoalActionDispatchResult,
    ) {
        val reserved = permit as? GoalActionExecutionPermit.Reserved ?: return
        val actual = successfulUsage(result, reserved.reservation) ?: return
        budgets.commit(
            accountId = reserved.accountId,
            reservationId = reserved.reservation.id,
            actualUsage = actual,
        )
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

    /**
     * Default private reminder/share authority is seeded only on a pristine policy ledger. Once any
     * policy history exists, especially a revoke, startup/retry must never silently recreate it.
     * An interrupted first bootstrap therefore fails closed rather than restoring authority.
     */
    private suspend fun ensurePrivateOwnerBaseline() = bootstrapMutex.withLock {
        val snapshot = ownerPolicy.snapshot()
        if (snapshot.revision != 0L) return@withLock
        DEFAULT_GRANTS.forEach { ownerPolicy.grant(it) }
    }

    private fun policyRequest(context: GoalActionContext): OwnerEffectRequest? = when (context.goal.intent) {
        IntentType.SCHEDULE -> OwnerEffectRequest(
            actorId = PRIVATE_OWNER,
            effect = OwnerEffectType.REMINDER,
            resource = REMINDER_RESOURCE,
            scope = OWNER_SCOPE,
            capabilityId = context.routing.plan.requirements.firstOrNull()?.capabilityId,
        )
        IntentType.COMMUNICATE -> OwnerEffectRequest(
            actorId = PRIVATE_OWNER,
            effect = OwnerEffectType.COMMUNICATION,
            resource = COMMUNICATION_RESOURCE,
            scope = OWNER_SCOPE,
            capabilityId = context.routing.plan.requirements.firstOrNull()?.capabilityId,
        )
        else -> null
    }

    private fun profile(intent: IntentType): GoalActionResourceProfile? = when (intent) {
        IntentType.QUERY,
        IntentType.STORE_OR_REMEMBER -> GoalActionResourceProfile(
            hardQuota = quota(5_000, 24, 64, 8, 0, 4),
            requested = usage(3_000, 8, 24, 2, 0, 2),
        )
        IntentType.SEARCH -> GoalActionResourceProfile(
            hardQuota = quota(6_000, 24, 96, 12, 0, 8),
            requested = usage(4_000, 16, 48, 4, 0, 6),
        )
        IntentType.CREATE_IMAGE -> GoalActionResourceProfile(
            hardQuota = quota(30_000, 256, 512, 160, 0, 8),
            requested = usage(20_000, 160, 256, 96, 0, 4),
            priority = HardwareWorkPriority.HIGH,
        )
        IntentType.TRANSFORM_IMAGE -> GoalActionResourceProfile(
            hardQuota = quota(20_000, 160, 384, 160, 0, 4),
            requested = usage(12_000, 96, 192, 96, 0, 2),
            priority = HardwareWorkPriority.HIGH,
        )
        IntentType.SCHEDULE,
        IntentType.COMMUNICATE -> GoalActionResourceProfile(
            hardQuota = quota(3_000, 8, 32, 4, 0, 2),
            requested = usage(1_500, 4, 16, 1, 0, 1),
            priority = HardwareWorkPriority.HIGH,
        )
        else -> null
    }

    private data class GoalActionResourceProfile(
        val hardQuota: ResourceBudgetQuota,
        val requested: ResourceBudgetUsage,
        val priority: HardwareWorkPriority = HardwareWorkPriority.NORMAL,
    )

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
        const val OWNER_SCOPE = "private-apk-goal-action"
        const val REMINDER_RESOURCE = "goal://local-reminder"
        const val COMMUNICATION_RESOURCE = "goal://local-share-preparation"
        val PRIVATE_OWNER = OwnerActorId("private-owner")
        val DEFAULT_GRANTS = listOf(
            OwnerPolicyGrant.create(
                actorId = PRIVATE_OWNER,
                effect = OwnerEffectType.REMINDER,
                resource = OwnerResourceSelector(OwnerResourceSelectorType.EXACT, REMINDER_RESOURCE),
                scope = OWNER_SCOPE,
                validFrom = Instant.EPOCH,
            ),
            OwnerPolicyGrant.create(
                actorId = PRIVATE_OWNER,
                effect = OwnerEffectType.COMMUNICATION,
                resource = OwnerResourceSelector(OwnerResourceSelectorType.EXACT, COMMUNICATION_RESOURCE),
                scope = OWNER_SCOPE,
                validFrom = Instant.EPOCH,
            ),
        )
    }
}
