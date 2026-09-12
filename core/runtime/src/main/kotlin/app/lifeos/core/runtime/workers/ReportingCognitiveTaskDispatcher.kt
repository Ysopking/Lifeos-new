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
            val result = worker.execute(task)
            observer.onExecutionResult(result)
            CausalCognitionTaskObserverRegistry.current()?.onExecutionResult(result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            observer.onDispatchFailure(task, error)
            CausalCognitionTaskObserverRegistry.current()?.onDispatchFailure(task, error)
            throw error
        }
    }
}
