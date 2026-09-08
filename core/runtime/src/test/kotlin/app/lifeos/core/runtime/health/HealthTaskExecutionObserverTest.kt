package app.lifeos.core.runtime.health

import app.lifeos.core.field.ConvergenceStatus
import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.FieldRunId
import app.lifeos.core.field.FieldSnapshotId
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.runtime.RuntimeFailure
import app.lifeos.core.runtime.RuntimeFailureCategory
import app.lifeos.core.runtime.field.FieldShadowExecution
import app.lifeos.core.runtime.field.FieldShadowState
import app.lifeos.core.runtime.field.universalFieldShadowHealthNodeId
import app.lifeos.core.runtime.workers.CognitiveTaskExecutionResult
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HealthTaskExecutionObserverTest {
    private val observedAt = Instant.parse("2026-09-08T03:45:00Z")
    private val workerNodeId = HealthNodeId("worker:test")

    @Test
    fun successfulTaskMarksWorkerHealthy() = runTest {
        val graph = HealthGraph(now = { observedAt })
        val observer = HealthTaskExecutionObserver(
            workerNodeId = workerNodeId,
            graph = graph,
            now = { observedAt },
        )

        observer.onExecutionResult(result(finalState = TaskState.COMPLETED))

        val worker = assertNotNull(graph.node(workerNodeId))
        assertEquals(HealthScope.WORKER, worker.scope)
        assertEquals(HealthState.HEALTHY, worker.state)
        assertEquals(observedAt, worker.lastHealthyAt)
        assertEquals(0, worker.consecutiveFailures)
    }

    @Test
    fun structuredStorageFailureIsRoutedToStorageNodeWithoutPoisoningWorker() = runTest {
        val graph = HealthGraph(now = { observedAt })
        val observer = HealthTaskExecutionObserver(
            workerNodeId = workerNodeId,
            graph = graph,
            now = { observedAt },
        )
        val failure = RuntimeFailure(
            category = RuntimeFailureCategory.STORAGE,
            source = "photon-repository",
            message = "vault read failed",
            recoverable = true,
        )

        observer.onExecutionResult(
            result(
                finalState = TaskState.FAILED,
                failures = listOf(failure),
            ),
        )

        assertEquals(HealthState.HEALTHY, graph.node(workerNodeId)?.state)
        val storage = assertNotNull(graph.node(HealthNodeId("storage:photon-repository")))
        assertEquals(HealthScope.STORAGE_ENGINE, storage.scope)
        assertEquals(HealthState.DEGRADED, storage.state)
        assertEquals(1, storage.consecutiveFailures)
    }

    @Test
    fun completedFieldShadowUsesCanonicalFieldHealthIdentity() = runTest {
        val graph = HealthGraph(now = { observedAt })
        val observer = HealthTaskExecutionObserver(
            workerNodeId = workerNodeId,
            graph = graph,
            now = { observedAt },
        )
        val domain = FieldDomainId("domain:test")
        val shadow = FieldShadowExecution(
            state = FieldShadowState.COMPLETED,
            domainId = domain,
            runId = FieldRunId("run:test"),
            snapshotId = FieldSnapshotId("snapshot:test"),
            convergenceStatus = ConvergenceStatus.UNRESOLVED,
        )

        observer.onExecutionResult(result(fieldShadow = shadow))

        val field = assertNotNull(graph.node(universalFieldShadowHealthNodeId(domain)))
        assertEquals(HealthScope.FIELD, field.scope)
        assertEquals(HealthState.HEALTHY, field.state)
    }

    @Test
    fun failedFieldShadowDegradesCanonicalFieldNode() = runTest {
        val graph = HealthGraph(now = { observedAt })
        val observer = HealthTaskExecutionObserver(
            workerNodeId = workerNodeId,
            graph = graph,
            now = { observedAt },
        )
        val domain = FieldDomainId("domain:test")

        observer.onExecutionResult(
            result(
                fieldShadow = FieldShadowExecution.failed(
                    domainId = domain,
                    message = "field persistence failed",
                ),
            ),
        )

        val field = assertNotNull(graph.node(universalFieldShadowHealthNodeId(domain)))
        assertEquals(HealthScope.FIELD, field.scope)
        assertEquals(HealthState.DEGRADED, field.state)
    }

    @Test
    fun blockedFieldShadowDoesNotCreateSyntheticObservation() = runTest {
        val graph = HealthGraph(now = { observedAt })
        val observer = HealthTaskExecutionObserver(
            workerNodeId = workerNodeId,
            graph = graph,
            now = { observedAt },
        )
        val domain = FieldDomainId("domain:test")

        observer.onExecutionResult(
            result(fieldShadow = FieldShadowExecution.blocked(domain, "health-gate-blocked")),
        )

        assertNull(graph.node(universalFieldShadowHealthNodeId(domain)))
        assertEquals(HealthState.HEALTHY, graph.node(workerNodeId)?.state)
    }

    private fun result(
        finalState: TaskState = TaskState.COMPLETED,
        failures: List<RuntimeFailure> = emptyList(),
        fieldShadow: FieldShadowExecution? = null,
    ) = CognitiveTaskExecutionResult(
        taskId = TaskId("task:test"),
        photonId = null,
        finalState = finalState,
        influences = emptyList(),
        failures = failures,
        fieldShadow = fieldShadow,
    )
}
