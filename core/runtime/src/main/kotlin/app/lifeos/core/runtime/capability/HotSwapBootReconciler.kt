package app.lifeos.core.runtime.capability

import app.lifeos.core.runtime.resource.ResourceBudgetAccountId
import app.lifeos.core.runtime.resource.ResourceBudgetCoordinator
import app.lifeos.core.runtime.resource.ResourceBudgetReservationState

data class HotSwapBootReconciliationReport(
    val committedRestored: Int,
    val pendingRolledBack: Int,
    val terminalStandbyRestored: Int,
    val budgetsCommitted: Int = 0,
    val budgetsReleased: Int = 0,
)

/**
 * Runs after generated-tool state rehydration and before normal runtime execution.
 * Only durable COMMITTED swaps may expose the candidate. Any non-terminal transaction is restored
 * to the previous provider and closed as ROLLED_BACK, because fresh owner/budget authority cannot be
 * reconstructed from disk. Durable V16 reservations are settled to the same outcome so a crash
 * cannot leak held budget or erase committed resource consumption.
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
        var budgetsCommitted = 0
        var budgetsReleased = 0
        ledger.all().forEach { transaction ->
            when (transaction.state) {
                HotSwapState.COMMITTED -> {
                    capabilities.applyRestoredHotSwap(
                        capabilityId = transaction.capabilityId,
                        previousProviderId = transaction.previousToolId,
                        candidateProviderId = transaction.candidateToolId,
                        committed = true,
                    )
                    if (settleBudget(transaction, committed = true)) budgetsCommitted += 1
                    committed += 1
                }
                HotSwapState.PREPARED,
                HotSwapState.CANDIDATE_PROMOTED -> {
                    capabilities.applyRestoredHotSwap(
                        capabilityId = transaction.capabilityId,
                        previousProviderId = transaction.previousToolId,
                        candidateProviderId = transaction.candidateToolId,
                        committed = false,
                    )
                    val rolled = ledger.markRolledBack(transaction, "boot-rollback-uncommitted-hot-swap")
                    if (settleBudget(rolled, committed = false)) budgetsReleased += 1
                    rolledBack += 1
                }
                HotSwapState.ROLLED_BACK,
                HotSwapState.BLOCKED -> {
                    capabilities.applyRestoredHotSwap(
                        capabilityId = transaction.capabilityId,
                        previousProviderId = transaction.previousToolId,
                        candidateProviderId = transaction.candidateToolId,
                        committed = false,
                    )
                    if (settleBudget(transaction, committed = false)) budgetsReleased += 1
                    terminalStandby += 1
                }
            }
        }
        return HotSwapBootReconciliationReport(
            committedRestored = committed,
            pendingRolledBack = rolledBack,
            terminalStandbyRestored = terminalStandby,
            budgetsCommitted = budgetsCommitted,
            budgetsReleased = budgetsReleased,
        )
    }

    private suspend fun settleBudget(transaction: HotSwapSnapshot, committed: Boolean): Boolean {
        val coordinator = budgets ?: return false
        val accountId = ResourceBudgetAccountId("hot-swap:${transaction.transactionId.value}")
        val account = coordinator.currentOrNull(accountId) ?: return false
        val reservation = account.reservations.singleOrNull {
            it.idempotencyKey == transaction.transactionId.value
        }
        if (reservation == null) {
            require(transaction.state == HotSwapState.PREPARED || transaction.state == HotSwapState.BLOCKED) {
                "Durable hot-swap state requires its V16 reservation"
            }
            return false
        }

        return when {
            committed && reservation.state == ResourceBudgetReservationState.RESERVED -> {
                coordinator.commit(accountId, reservation.id, reservation.reserved)
                true
            }
            committed && reservation.state == ResourceBudgetReservationState.COMMITTED -> false
            committed -> error("Committed hot-swap cannot have a released V16 reservation")
            reservation.state == ResourceBudgetReservationState.RESERVED -> {
                coordinator.release(accountId, reservation.id)
                true
            }
            reservation.state == ResourceBudgetReservationState.RELEASED -> false
            else -> error("Non-committed hot-swap cannot retain committed V16 usage")
        }
    }
}
