package app.lifeos.core.runtime

import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.runtime.workers.CognitiveTaskExecutionResult
import app.lifeos.core.runtime.workers.DurableTaskExecutionObserver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class DurableRuntimeStateBridge(
    private val health: app.lifeos.core.runtime.health.RecoveryCoordinator? = null,
) : DurableTaskExecutionObserver {
    private val mutableState = MutableStateFlow(RuntimeState())
    val state: StateFlow<RuntimeState> = mutableState.asStateFlow()

    fun markStarting() {
        mutableState.update { it.copy(status = RuntimeStatus.STARTING) }
    }

    fun markRunning() {
        mutableState.update { it.copy(status = RuntimeStatus.RUNNING) }
    }

    fun markStopping() {
        mutableState.update { it.copy(status = RuntimeStatus.STOPPING) }
    }

    fun markStopped() {
        mutableState.update { it.copy(status = RuntimeStatus.STOPPED) }
    }

    fun markFailed(error: Throwable) {
        health?.graph?.record(app.lifeos.core.runtime.health.HealthNodes.Runtime,
            app.lifeos.core.runtime.health.HealthState.UNHEALTHY, "runtime-failed")
        health?.safeMode?.enter("Runtime")
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
        health?.graph?.record(app.lifeos.core.runtime.health.HealthNodes.Worker,
            app.lifeos.core.runtime.health.HealthState.DEGRADED, "dispatch-failure")
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

