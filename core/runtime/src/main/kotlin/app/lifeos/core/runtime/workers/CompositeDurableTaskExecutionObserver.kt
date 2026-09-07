package app.lifeos.core.runtime.workers

import app.lifeos.core.model.task.LifeTask
import kotlinx.coroutines.CancellationException

/**
 * Keeps the primary observer authoritative while allowing additional telemetry
 * observers to run best-effort. Secondary observer failures never fail a task.
 */
class CompositeDurableTaskExecutionObserver(
    private val primary: DurableTaskExecutionObserver,
    private val secondary: List<DurableTaskExecutionObserver>,
) : DurableTaskExecutionObserver {
    override suspend fun onExecutionResult(result: CognitiveTaskExecutionResult) {
        primary.onExecutionResult(result)
        secondary.forEach { observer ->
            try {
                observer.onExecutionResult(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Monitoring must not change task outcome.
            }
        }
    }

    override suspend fun onDispatchFailure(task: LifeTask, error: Exception) {
        primary.onDispatchFailure(task, error)
        secondary.forEach { observer ->
            try {
                observer.onDispatchFailure(task, error)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Monitoring must not change dispatch semantics.
            }
        }
    }
}
