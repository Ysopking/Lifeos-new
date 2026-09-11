package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.task.TaskState
import app.lifeos.core.runtime.workers.CognitiveTaskExecutionResult
import app.lifeos.core.runtime.workers.DurableTaskExecutionObserver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Refills durable cognition capacity after terminal worker results.
 *
 * Reconciliation is serialized and bounded by [DurableCognitionReconciler]. It does not retain a
 * queue of its own: uncovered photons remain durable in PhotonStore until TaskStore has capacity.
 */
class DurableCognitionRecoveryObserver(
    private val reconcile: suspend () -> Unit,
) : DurableTaskExecutionObserver {
    private val mutex = Mutex()

    constructor(reconciler: DurableCognitionReconciler) : this(
        reconcile = {
            reconciler.reconcile()
            Unit
        }
    )

    override suspend fun onExecutionResult(result: CognitiveTaskExecutionResult) {
        if (!result.finalState.isTerminal()) return
        mutex.withLock {
            reconcile()
        }
    }

    private fun TaskState.isTerminal(): Boolean = when (this) {
        TaskState.COMPLETED,
        TaskState.SUPERSEDED,
        TaskState.FAILED,
        TaskState.CANCELLED -> true

        else -> false
    }
}
