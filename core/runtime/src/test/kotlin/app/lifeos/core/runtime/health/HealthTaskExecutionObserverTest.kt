package app.lifeos.core.runtime.health

import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.runtime.RuntimeFailure
import app.lifeos.core.runtime.RuntimeFailureCategory
import app.lifeos.core.runtime.workers.CognitiveTaskExecutionResult
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class HealthTaskExecutionObserverTest {
    private val t0 = Instant.parse("2026-09-07T18:00:00Z")

    @Test
    fun fieldFailureDegradesFieldNodeWhileWorkerRemainsHealthy() = runTest {
        val graph = HealthGraph(now = { t0 })
        val workerId = HealthNodeId("worker:cognitive-worker-0")
        val observer = HealthTaskExecutionObserver(
            workerNodeId = workerId,
            graph = graph,
            now = { t0 },
        )

        observer.onExecutionResult(
            CognitiveTaskExecutionResult(
                taskId = TaskId("task-1"),
                photonId = null,
                finalState = TaskState.RETRY_WAIT,
                influences = emptyList(),
                failures = listOf(
                    RuntimeFailure(
                        category = RuntimeFailureCategory.FIELD,
                        source = "Thought Matrix",
                        message = "temporary field failure",
                    )
                ),
            )
        )

        val worker = assertNotNull(graph.node(workerId))
        val field = assertNotNull(graph.node(HealthNodeId("field:thought_matrix")))
        assertEquals(HealthScope.WORKER, worker.scope)
        assertEquals(HealthState.HEALTHY, worker.state)
        assertEquals(HealthScope.FIELD, field.scope)
        assertEquals(HealthState.DEGRADED, field.state)
    }

    @Test
    fun workerInvariantOverridesHealthyExecutionObservation() = runTest {
        val graph = HealthGraph(now = { t0 })
        val workerId = HealthNodeId("worker:cognitive-worker-0")
        val observer = HealthTaskExecutionObserver(workerId, graph, now = { t0 })

        observer.onExecutionResult(
            CognitiveTaskExecutionResult(
                taskId = TaskId("task-2"),
                photonId = null,
                finalState = TaskState.FAILED,
                influences = emptyList(),
                failures = listOf(
                    RuntimeFailure(
                        category = RuntimeFailureCategory.INVARIANT,
                        source = "cognitive-worker",
                        message = "unsupported state",
                        recoverable = false,
                    )
                ),
            )
        )

        val worker = assertNotNull(graph.node(workerId))
        assertEquals(HealthState.UNHEALTHY, worker.state)
        assertEquals(1, worker.consecutiveFailures)
    }
}
