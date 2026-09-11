package app.lifeos.core.runtime.capability

import app.lifeos.core.runtime.resource.ResourceBudgetAccountId
import app.lifeos.core.runtime.resource.ResourceBudgetCoordinator
import app.lifeos.core.runtime.resource.ResourceBudgetReservationState

data class HotSwapBootReconciliationReport(
    val committedRestored: Int,
    val pendingRolledBack: Int,
    val terminalStandbyRestored: Int,
    val revertsCompleted: Int = 0,
    val budgetsCommitted: Int = 0,
    val budgetsReleased: Int = 0,
)

/**
 * Runs after generated-tool state rehydration and before normal runtime execution.
 * Initial cutovers and post-commit reverts are both replayed from durable intent. Resource
 * reservations are settled to the same durable outcome so crashes cannot leak held budget.
 */
class HotSwapBootReconciler(
    private val ledger: HotSwapLedger,
    private val capabilities: CapabilityRegistry,
    private val budgets: ResourceBudgetCoordinator? = null,
) {
    suspend fun reconcile(): HotSwapBootReconciliationReport {
        var committed = 0
        var rolledBack = 0
        var terminalStandby = 0
        var revertsCompleted = 0
        var budgetsCommitted = 0
        var budgetsReleased = 0
        ledger.all().forEach { transaction ->
            when (transaction.state) {
                HotSwapState.COMMITTED -> {
                    applyCommitted(transaction)
                    if (settleInitialBudget(transaction, committed = true)) budgetsCommitted += 1
                    committed += 1
                }
                HotSwapState.REVERT_PREPARED -> {
                    applyReverted(transaction)
                    if (settleInitialBudget(transaction, committed = true)) budgetsCommitted += 1
                    val reverted = ledger.markReverted(transaction, "boot-completed-authorized-hot-swap-revert")
                    if (settleRevertBudget(reverted, committed = true)) budgetsCommitted += 1
                    revertsCompleted += 1
                }
                HotSwapState.REVERTED -> {
                    applyReverted(transaction)
                    if (settleInitialBudget(transaction, committed = true)) budgetsCommitted += 1
                    if (settleRevertBudget(transaction, committed = true)) budgetsCommitted += 1
                    revertsCompleted += 1
                }
                HotSwapState.PREPARED,
                HotSwapState.CANDIDATE_PROMOTED -> {
                    applyReverted(transaction)
                    val rolled = ledger.markRolledBack(transaction, "boot-rollback-uncommitted-hot-swap")
                    if (settleInitialBudget(rolled, committed = false)) budgetsReleased += 1
                    rolledBack += 1
                }
                HotSwapState.ROLLED_BACK,
                HotSwapState.BLOCKED -> {
                    applyReverted(transaction)
                    if (settleInitialBudget(transaction, committed = false)) budgetsReleased += 1
                    terminalStandby += 1
                }
            }
        }
        return HotSwapBootReconciliationReport(
            committedRestored = committed,
            pendingRolledBack = rolledBack,
            terminalStandbyRestored = terminalStandby,
            revertsCompleted = revertsCompleted,
            budgetsCommitted = budgetsCommitted,
            budgetsReleased = budgetsReleased,
        )
    }

    private suspend fun applyCommitted(transaction: HotSwapSnapshot) {
        capabilities.applyRestoredHotSwap(
            capabilityId = transaction.capabilityId,
            previousProviderId = transaction.previousToolId,
            candidateProviderId = transaction.candidateToolId,
            committed = true,
        )
    }

    private suspend fun applyReverted(transaction: HotSwapSnapshot) {
        capabilities.applyRestoredHotSwap(
            capabilityId = transaction.capabilityId,
            previousProviderId = transaction.previousToolId,
            candidateProviderId = transaction.candidateToolId,
            committed = false,
        )
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
}
