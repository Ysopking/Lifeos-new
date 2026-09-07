package app.lifeos.core.runtime.health

import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.runtime.RuntimeFailure
import app.lifeos.core.runtime.RuntimeFailureCategory
import app.lifeos.core.runtime.workers.CognitiveTaskExecutionResult
import app.lifeos.core.runtime.workers.DurableTaskExecutionObserver
import java.time.Instant

class HealthTaskExecutionObserver(
    private val workerNodeId: HealthNodeId,
    private val graph: HealthGraph,
    private val classifier: FailureClassifier = FailureClassifier(),
    private val now: () -> Instant = Instant::now,
) : DurableTaskExecutionObserver {
    override suspend fun onExecutionResult(result: CognitiveTaskExecutionResult) {
        val observedAt = now()
        graph.recordHealthy(
            id = workerNodeId,
            source = "cognitive-worker",
            message = "Task ${result.taskId.value} reached ${result.finalState}",
            observedAt = observedAt,
        )

        if (result.failures.isEmpty()) {
            if (result.finalState == TaskState.FAILED) {
                graph.recordFailure(
                    workerNodeId,
                    RuntimeFailure(
                        category = RuntimeFailureCategory.UNKNOWN,
                        source = "cognitive-worker",
                        message = "Task ${result.taskId.value} failed without structured failure",
                    ),
                    observedAt,
                )
            }
            return
        }

        result.failures.forEach { failure ->
            val classification = classifier.classify(failure)
            graph.recordFailure(
                id = nodeIdFor(failure, classification.scope),
                failure = failure,
                observedAt = observedAt,
            )
        }
    }

    override suspend fun onDispatchFailure(task: LifeTask, error: Exception) {
        graph.recordFailure(
            id = workerNodeId,
            failure = RuntimeFailure(
                category = RuntimeFailureCategory.UNKNOWN,
                source = "cognitive-worker-dispatch",
                message = error.message ?: error::class.simpleName ?: "Worker dispatch failed",
                photonId = task.inputPhotonIds.singleOrNull(),
            ),
            observedAt = now(),
        )
    }

    private fun nodeIdFor(failure: RuntimeFailure, scope: HealthScope): HealthNodeId {
        if (scope == HealthScope.WORKER) return workerNodeId
        val prefix = when (scope) {
            HealthScope.KERNEL -> "kernel"
            HealthScope.RUNTIME -> "runtime"
            HealthScope.SCHEDULER -> "scheduler"
            HealthScope.FIELD -> "field"
            HealthScope.STORAGE_ITEM -> "storage-item"
            HealthScope.STORAGE_ENGINE -> "storage"
            HealthScope.MEMORY -> "memory"
            HealthScope.CHAT -> "chat"
            HealthScope.PLANNER -> "planner"
            HealthScope.AUTOMATION -> "automation"
            HealthScope.FINANCE -> "finance"
            HealthScope.EXTERNAL_APP -> "external-app"
            HealthScope.BUILD_STUDIO -> "build-studio"
            HealthScope.UNKNOWN -> "unknown"
            HealthScope.WORKER -> "worker"
        }
        val source = failure.source
            .lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "_")
            .trim('_')
            .ifBlank { "unknown" }
            .take(96)
        return HealthNodeId("$prefix:$source")
    }
}
