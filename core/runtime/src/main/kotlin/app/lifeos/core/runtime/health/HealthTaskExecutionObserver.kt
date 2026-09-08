package app.lifeos.core.runtime.health

import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.runtime.RuntimeFailure
import app.lifeos.core.runtime.RuntimeFailureCategory
import app.lifeos.core.runtime.field.FieldShadowState
import app.lifeos.core.runtime.field.universalFieldShadowHealthNodeId
import app.lifeos.core.runtime.workers.CognitiveTaskExecutionResult
import app.lifeos.core.runtime.workers.DurableTaskExecutionObserver
import java.time.Instant

/**
 * Best-effort health projection for productive durable task execution.
 *
 * The worker node represents execution machinery, while structured failures are routed to their
 * actual scope. Universal-field shadow health is observational and never changes the task result.
 */
class HealthTaskExecutionObserver(
    private val workerNodeId: HealthNodeId,
    private val graph: HealthGraph,
    private val classifier: FailureClassifier = FailureClassifier(),
    private val now: () -> Instant = Instant::now,
) : DurableTaskExecutionObserver {
    override suspend fun onExecutionResult(result: CognitiveTaskExecutionResult) {
        graph.register(workerNodeId, HealthScope.WORKER)
        val observedAt = now()
        graph.recordHealthy(
            id = workerNodeId,
            source = "cognitive-worker",
            message = "Task ${result.taskId.value} reached ${result.finalState}",
            observedAt = observedAt,
        )

        if (result.failures.isEmpty() && result.finalState == TaskState.FAILED) {
            graph.recordFailure(
                id = workerNodeId,
                failure = RuntimeFailure(
                    category = RuntimeFailureCategory.UNKNOWN,
                    source = "cognitive-worker",
                    message = "Task ${result.taskId.value} failed without structured failure",
                    recoverable = false,
                    photonId = result.photonId,
                ),
                observedAt = observedAt,
            )
        }

        result.failures.forEach { failure ->
            val classification = classifier.classify(failure)
            graph.recordFailure(
                id = nodeIdFor(failure, classification.scope),
                failure = failure,
                observedAt = observedAt,
            )
        }

        val shadow = result.fieldShadow ?: return
        val domainId = shadow.domainId ?: return
        val fieldNodeId = universalFieldShadowHealthNodeId(domainId)
        when (shadow.state) {
            FieldShadowState.COMPLETED -> {
                graph.register(fieldNodeId, HealthScope.FIELD)
                graph.recordHealthy(
                    id = fieldNodeId,
                    source = "universal-field-shadow",
                    message = "Shadow ${shadow.convergenceStatus} snapshot ${shadow.snapshotId}",
                    observedAt = observedAt,
                )
            }
            FieldShadowState.FAILED -> graph.recordFailure(
                id = fieldNodeId,
                failure = RuntimeFailure(
                    category = RuntimeFailureCategory.FIELD,
                    source = "universal-field-shadow",
                    message = shadow.message ?: "Universal field shadow failed",
                    recoverable = true,
                    photonId = result.photonId,
                ),
                observedAt = observedAt,
            )
            FieldShadowState.BLOCKED -> Unit
        }
    }

    override suspend fun onDispatchFailure(task: LifeTask, error: Exception) {
        graph.register(workerNodeId, HealthScope.WORKER)
        graph.recordFailure(
            id = workerNodeId,
            failure = RuntimeFailure(
                category = RuntimeFailureCategory.UNKNOWN,
                source = "cognitive-worker-dispatch",
                message = error.message ?: error::class.simpleName ?: "Worker dispatch failed",
                recoverable = true,
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
            HealthScope.WORKER -> "worker"
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
