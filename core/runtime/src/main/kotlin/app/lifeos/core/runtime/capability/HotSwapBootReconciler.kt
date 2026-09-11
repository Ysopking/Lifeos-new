package app.lifeos.core.runtime.capability

data class HotSwapBootReconciliationReport(
    val committedRestored: Int,
    val pendingRolledBack: Int,
    val terminalStandbyRestored: Int,
)

/**
 * Runs after generated-tool state rehydration and before normal runtime execution.
 * Only durable COMMITTED swaps may expose the candidate. Any non-terminal transaction is restored
 * to the previous provider and closed as ROLLED_BACK, because fresh owner/budget authority cannot be
 * reconstructed from disk.
 */
class HotSwapBootReconciler(
    private val ledger: HotSwapLedger,
    private val capabilities: CapabilityRegistry,
) {
    suspend fun reconcile(): HotSwapBootReconciliationReport {
        var committed = 0
        var rolledBack = 0
        var terminalStandby = 0
        ledger.all().forEach { transaction ->
            when (transaction.state) {
                HotSwapState.COMMITTED -> {
                    capabilities.applyRestoredHotSwap(
                        capabilityId = transaction.capabilityId,
                        previousProviderId = transaction.previousToolId,
                        candidateProviderId = transaction.candidateToolId,
                        committed = true,
                    )
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
                    ledger.markRolledBack(transaction, "boot-rollback-uncommitted-hot-swap")
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
                    terminalStandby += 1
                }
            }
        }
        return HotSwapBootReconciliationReport(
            committedRestored = committed,
            pendingRolledBack = rolledBack,
            terminalStandbyRestored = terminalStandby,
        )
    }
}
