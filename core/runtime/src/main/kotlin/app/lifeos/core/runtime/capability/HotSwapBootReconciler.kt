package app.lifeos.core.runtime.capability

import app.lifeos.core.runtime.policy.OwnerActorId
import app.lifeos.core.runtime.policy.OwnerEffectExposureResult
import app.lifeos.core.runtime.policy.OwnerEffectRequest
import app.lifeos.core.runtime.policy.OwnerEffectType
import app.lifeos.core.runtime.policy.OwnerPolicyEffectGate
import app.lifeos.core.runtime.policy.OwnerPolicyLedger
import app.lifeos.core.runtime.resource.ResourceBudgetAccountId
import app.lifeos.core.runtime.resource.ResourceBudgetCoordinator
import app.lifeos.core.runtime.resource.ResourceBudgetReservationId
import app.lifeos.core.runtime.resource.ResourceBudgetReservationState
import app.lifeos.core.runtime.trace.DecisionTraceRuntimeRegistry
import java.time.Instant

data class HotSwapBootReconciliationReport(
    val committedRestored: Int,
    val pendingRolledBack: Int,
    val terminalStandbyRestored: Int,
    val revertsCompleted: Int = 0,
    val budgetsCommitted: Int = 0,
    val budgetsReleased: Int = 0,
    val ownerPolicyBlocked: Int = 0,
)

/**
 * Runs after generated-tool state rehydration and before normal runtime execution.
 * Initial cutovers and post-commit reverts are both replayed from durable intent. Resource
 * reservations are settled to the same durable outcome so crashes cannot leak held budget.
 *
 * V14 invariant: a durable hot-swap state is evidence, not evergreen routing authority. Every boot
 * routing mutation is exposed through the current Owner Policy immediately around the registry
 * mutation. Missing/revoked/corrupt authority removes both transaction providers from routing while
 * preserving the durable transaction for diagnostics/recovery.
 */
class HotSwapBootReconciler(
    private val ledger: HotSwapLedger,
    private val capabilities: CapabilityRegistry,
    private val budgets: ResourceBudgetCoordinator? = null,
    private val ownerPolicy: OwnerPolicyLedger? = null,
    private val tools: GeneratedToolRegistry? = null,
    private val actorId: OwnerActorId? = null,
    private val ownerScope: String? = null,
) {
    init { require(ownerScope == null || ownerScope.isNotBlank()) }

    suspend fun reconcile(): HotSwapBootReconciliationReport {
        var committed = 0
        var rolledBack = 0
        var terminalStandby = 0
        var revertsCompleted = 0
        var budgetsCommitted = 0
        var budgetsReleased = 0
        var policyBlocked = 0
        ledger.all().forEach { transaction ->
            when (transaction.state) {
                HotSwapState.COMMITTED -> {
                    if (applyRouting(transaction, committed = true, useRevertBudget = false)) {
                        committed += 1
                    } else {
                        policyBlocked += 1
                    }
                    if (settleInitialBudget(transaction, committed = true)) budgetsCommitted += 1
                }
                HotSwapState.REVERT_PREPARED -> {
                    if (settleInitialBudget(transaction, committed = true)) budgetsCommitted += 1
                    if (applyRouting(transaction, committed = false, useRevertBudget = true)) {
                        val reverted = ledger.markReverted(
                            transaction,
                            "boot-completed-owner-authorized-hot-swap-revert",
                        )
                        if (settleRevertBudget(reverted, committed = true)) budgetsCommitted += 1
                        revertsCompleted += 1
                    } else {
                        // Keep the durable revert intent and its reservation intact. It may only
                        // complete after a future boot/exposure sees current owner authority.
                        policyBlocked += 1
                    }
                }
                HotSwapState.REVERTED -> {
                    if (applyRouting(transaction, committed = false, useRevertBudget = true)) {
                        revertsCompleted += 1
                    } else {
                        policyBlocked += 1
                    }
                    if (settleInitialBudget(transaction, committed = true)) budgetsCommitted += 1
                    if (settleRevertBudget(transaction, committed = true)) budgetsCommitted += 1
                }
                HotSwapState.PREPARED,
                HotSwapState.CANDIDATE_PROMOTED -> {
                    if (!applyRouting(transaction, committed = false, useRevertBudget = false)) {
                        policyBlocked += 1
                    }
                    val rolled = ledger.markRolledBack(transaction, "boot-rollback-uncommitted-hot-swap")
                    if (settleInitialBudget(rolled, committed = false)) budgetsReleased += 1
                    rolledBack += 1
                }
                HotSwapState.ROLLED_BACK,
                HotSwapState.BLOCKED -> {
                    if (applyRouting(transaction, committed = false, useRevertBudget = false)) {
                        terminalStandby += 1
                    } else {
                        policyBlocked += 1
                    }
                    if (settleInitialBudget(transaction, committed = false)) budgetsReleased += 1
                }
            }
            // V15 observes the post-reconciliation durable state. It never supplies authority to
            // applyRouting and cannot turn a blocked restore into a successful one.
            ledger.snapshot(transaction.transactionId)?.let { current ->
                DecisionTraceRuntimeRegistry.currentOrNull()?.recordHotSwap(
                    snapshot = current,
                    recordedAt = Instant.now(),
                )
            }
        }
        return HotSwapBootReconciliationReport(
            committedRestored = committed,
            pendingRolledBack = rolledBack,
            terminalStandbyRestored = terminalStandby,
            revertsCompleted = revertsCompleted,
            budgetsCommitted = budgetsCommitted,
            budgetsReleased = budgetsReleased,
            ownerPolicyBlocked = policyBlocked,
        )
    }

    private suspend fun applyRouting(
        transaction: HotSwapSnapshot,
        committed: Boolean,
        useRevertBudget: Boolean,
    ): Boolean {
        val policy = ownerPolicy
        val actor = actorId
        val scope = ownerScope
        if (policy == null || actor == null || scope == null) {
            unregisterPair(transaction)
            return false
        }

        val targetToolId = if (committed) transaction.candidateToolId else transaction.previousToolId
        val targetRecord = tools?.get(targetToolId)
        val exactDurableVersion = targetRecord?.manifest?.buildHash
            ?: targetRecord?.manifest?.sourceHash
            ?: if (committed) {
                transaction.candidatePromotionEvidenceId
            } else {
                transaction.previousPromotionEvidenceId
            }
        val binding = budgetBinding(transaction, useRevertBudget)
        val request = OwnerEffectRequest(
            actorId = actor,
            effect = OwnerEffectType.PROVIDER_ACTIVATION,
            resource = "hot-swap:${transaction.capabilityId.value}:${transaction.previousToolId}->${transaction.candidateToolId}",
            scope = scope,
            capabilityId = targetRecord?.manifest?.sourceCapability ?: transaction.capabilityId,
            providerVersion = exactDurableVersion,
            budgetAccountId = binding?.accountId,
            budgetReservationId = binding?.reservationId,
        )
        return when (
            OwnerPolicyEffectGate(policy).expose(request) {
                capabilities.applyRestoredHotSwap(
                    capabilityId = transaction.capabilityId,
                    previousProviderId = transaction.previousToolId,
                    candidateProviderId = transaction.candidateToolId,
                    committed = committed,
                )
            }
        ) {
            is OwnerEffectExposureResult.Exposed -> true
            is OwnerEffectExposureResult.Blocked -> {
                unregisterPair(transaction)
                false
            }
        }
    }

    private suspend fun unregisterPair(transaction: HotSwapSnapshot) {
        capabilities.unregister(transaction.capabilityId, transaction.previousToolId)
        capabilities.unregister(transaction.capabilityId, transaction.candidateToolId)
    }

    private suspend fun budgetBinding(
        transaction: HotSwapSnapshot,
        revert: Boolean,
    ): BudgetBinding? {
        val coordinator = budgets ?: return null
        val accountId = if (revert) {
            ResourceBudgetAccountId("hot-swap-revert:${transaction.transactionId.value}")
        } else {
            ResourceBudgetAccountId("hot-swap:${transaction.transactionId.value}")
        }
        val key = if (revert) {
            "revert:${transaction.transactionId.value}"
        } else {
            transaction.transactionId.value
        }
        val account = coordinator.currentOrNull(accountId) ?: return null
        val reservation = account.reservations.singleOrNull { it.idempotencyKey == key } ?: return null
        return BudgetBinding(accountId, reservation.id)
    }

    private suspend fun settleInitialBudget(transaction: HotSwapSnapshot, committed: Boolean): Boolean =
        settleBudget(
            accountId = ResourceBudgetAccountId("hot-swap:${transaction.transactionId.value}"),
            idempotencyKey = transaction.transactionId.value,
            committed = committed,
            allowMissingReservation = transaction.state == HotSwapState.PREPARED || transaction.state == HotSwapState.BLOCKED,
        )

    private suspend fun settleRevertBudget(transaction: HotSwapSnapshot, committed: Boolean): Boolean =
        settleBudget(
            accountId = ResourceBudgetAccountId("hot-swap-revert:${transaction.transactionId.value}"),
            idempotencyKey = "revert:${transaction.transactionId.value}",
            committed = committed,
            allowMissingReservation = false,
        )

    private suspend fun settleBudget(
        accountId: ResourceBudgetAccountId,
        idempotencyKey: String,
        committed: Boolean,
        allowMissingReservation: Boolean,
    ): Boolean {
        val coordinator = budgets ?: return false
        val account = coordinator.currentOrNull(accountId)
        if (account == null) {
            require(allowMissingReservation) { "Durable hot-swap state requires its V16 budget account" }
            return false
        }
        val reservation = account.reservations.singleOrNull { it.idempotencyKey == idempotencyKey }
        if (reservation == null) {
            require(allowMissingReservation) { "Durable hot-swap state requires its V16 reservation" }
            return false
        }
        return when {
            committed && reservation.state == ResourceBudgetReservationState.RESERVED -> {
                coordinator.commit(accountId, reservation.id, reservation.reserved)
                true
            }
            committed && reservation.state == ResourceBudgetReservationState.COMMITTED -> false
            committed -> error("Committed hot-swap phase cannot have a released V16 reservation")
            reservation.state == ResourceBudgetReservationState.RESERVED -> {
                coordinator.release(accountId, reservation.id)
                true
            }
            reservation.state == ResourceBudgetReservationState.RELEASED -> false
            else -> error("Non-committed hot-swap phase cannot retain committed V16 usage")
        }
    }

    private data class BudgetBinding(
        val accountId: ResourceBudgetAccountId,
        val reservationId: ResourceBudgetReservationId,
    )
}
