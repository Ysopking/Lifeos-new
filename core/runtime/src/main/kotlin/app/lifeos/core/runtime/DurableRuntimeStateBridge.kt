package app.lifeos.core.runtime

import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.runtime.workers.CognitiveTaskExecutionResult
import app.lifeos.core.runtime.workers.DurableTaskExecutionObserver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class DurableRuntimeStateBridge : DurableTaskExecutionObserver {
    private val mutableState = MutableStateFlow(RuntimeState())
    val state: StateFlow<RuntimeState> = mutableState.asStateFlow()

    fun markStarting() {
        mutableState.update { it.copy(status = RuntimeStatus.STARTING) }
    }

    fun markRunning() {
        mutableState.update { it.copy(status = RuntimeStatus.RUNNING) }
    }

    fun markDegraded(failure: RuntimeFailure) {
        mutableState.update {
            it.copy(
                status = RuntimeStatus.DEGRADED,
                lastFailure = failure,
            )
        }
    }

    fun markStopping() {
        mutableState.update { it.copy(status = RuntimeStatus.STOPPING) }
    }

    fun markStopped() {
        mutableState.update { it.copy(status = RuntimeStatus.STOPPED) }
    }

    fun markFailed(error: Throwable) {
        mutableState.update { previous ->
            previous.copy(
                status = RuntimeStatus.FAILED,
                lastFailure = RuntimeFailure(
                    category = RuntimeFailureCategory.UNKNOWN,
                    source = "durable-runtime",
                    message = error.message ?: error::class.simpleName ?: "Durable runtime failure",
                    photonId = previous.lastPhotonId,
                ),
            )
        }
    }

    override suspend fun onExecutionResult(result: CognitiveTaskExecutionResult) {
        mutableState.update { previous ->
            val completed = result.finalState == TaskState.COMPLETED
            val failed = result.finalState == TaskState.FAILED
            previous.copy(
                processed = previous.processed + if (completed) 1 else 0,
                failed = previous.failed + if (failed) 1 else 0,
                lastPhotonId = result.photonId ?: previous.lastPhotonId,
                lastFailure = result.failures.lastOrNull() ?: previous.lastFailure,
                recentInfluences = (previous.recentInfluences + result.influences).takeLast(100),
            )
        }
    }

    override suspend fun onDispatchFailure(task: LifeTask, error: Exception) {
        mutableState.update { previous ->
            previous.copy(
                lastFailure = RuntimeFailure(
                    category = RuntimeFailureCategory.UNKNOWN,
                    source = "durable-dispatch",
                    message = error.message ?: error::class.simpleName ?: "Durable dispatch failure",
                    photonId = task.inputPhotonIds.singleOrNull(),
                ),
            )
        }
    }
}
