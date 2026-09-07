package app.lifeos.core.runtime.workers

import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.runtime.tasks.ClaimedTaskDispatcher
import kotlinx.coroutines.CancellationException

interface DurableTaskExecutionObserver {
    suspend fun onExecutionResult(result: CognitiveTaskExecutionResult)

    suspend fun onDispatchFailure(task: LifeTask, error: Exception) = Unit
}

class ReportingCognitiveTaskDispatcher(
    private val worker: CognitiveTaskWorker,
    private val observer: DurableTaskExecutionObserver,
) : ClaimedTaskDispatcher {
    override suspend fun dispatch(task: LifeTask) {
        try {
            observer.onExecutionResult(worker.execute(task))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            observer.onDispatchFailure(task, error)
            throw error
        }
    }
}
