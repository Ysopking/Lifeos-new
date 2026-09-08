package app.lifeos.core.runtime.health

import app.lifeos.core.field.ConvergenceStatus
import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.FieldRunId
import app.lifeos.core.field.FieldSnapshotId
import app.lifeos.core.model.PhotonId
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

class HealthTaskExecutionObserverTest {
    private val observedAt = Instant.parse("2026-09-08T08:00:00Z")
    private val workerNodeId = HealthNodeId("worker:cognitive-0")
    private val photonId = PhotonId("photon-1")

    @Test
    fun successfulExecutionMarksWorkerHealthy() = runTest {
        val graph = HealthGraph(now = { observedAt })
        val observer = observer(graph)

        observer.onExecutionResult(result(finalState = TaskState.COMPLETED))

        val node = assertNotNull(graph.node(workerNodeId))
        assertEquals(HealthScope.WORKER, node.scope)
        assertEquals(HealthState.HEALTHY, node.state)
        assertEquals(0, node.consecutiveFailures)
        assertEquals(observedAt, node.lastHealthyAt)
    }

    @Test
    fun structuredStorageFailureRoutesToStorageNodeWithoutPoisoningWorker() = runTest {
        val graph = HealthGraph(now = { observedAt })
        val observer = observer(graph)
        val failure = RuntimeFailure(
            category = RuntimeFailureCategory.STORAGE,
            source = "Photon Repository",
            message = "read failed",
            recoverable = true,
            photonId = photonId,
        )

        observer.onExecutionResult(
            result(
                finalState = TaskState.FAILED,
                failures = listOf(failure),
            ),
        )

        val worker = assertNotNull(graph.node(workerNodeId))
        assertEquals(HealthState.HEALTHY, worker.state)

        val storage = assertNotNull(graph.node(HealthNodeId("storage:photon_repository")))
        assertEquals(HealthScope.STORAGE_ENGINE, storage.scope)
        assertEquals(HealthState.DEGRADED, storage.state)
        assertEquals(1, storage.totalFailures)
    }

    @Test
    fun completedFieldShadowUsesCentralFieldHealthIdentity() = runTest {
        val graph = HealthGraph(now = { observedAt })
        val observer = observer(graph)
        val domainId = FieldDomainId("lifeos.runtime.photon.shadow")
        val shadow = FieldShadowExecution(
            state = FieldShadowState.COMPLETED,
            domainId = domainId,
            runId = FieldRunId("run-1"),
            snapshotId = FieldSnapshotId("snapshot-1"),
            convergenceStatus = ConvergenceStatus.CONVERGED,
        )

        observer.onExecutionResult(result(finalState = TaskState.COMPLETED, fieldShadow = shadow))

        val fieldNode = assertNotNull(graph.node(universalFieldShadowHealthNodeId(domainId)))
        assertEquals(HealthScope.FIELD, fieldNode.scope)
        assertEquals(HealthState.HEALTHY, fieldNode.state)
        assertEquals(0, fieldNode.consecutiveFailures)
    }

    @Test
    fun failedFieldShadowUsesSameCentralIdentityAndDegradesRecoverably() = runTest {
        val graph = HealthGraph(now = { observedAt })
        val observer = observer(graph)
        val domainId = FieldDomainId("lifeos.runtime.photon.shadow")

        observer.onExecutionResult(
            result(
                finalState = TaskState.COMPLETED,
                fieldShadow = FieldShadowExecution.failed(domainId, "snapshot write failed"),
            ),
        )

        val fieldNode = assertNotNull(graph.node(universalFieldShadowHealthNodeId(domainId)))
        assertEquals(HealthScope.FIELD, fieldNode.scope)
        assertEquals(HealthState.DEGRADED, fieldNode.state)
        assertEquals(1, fieldNode.consecutiveFailures)
        assertEquals(HealthState.HEALTHY, graph.node(workerNodeId)?.state)
    }

    @Test
    fun failedTaskWithoutStructuredFailureMarksWorkerUnhealthy() = runTest {
        val graph = HealthGraph(now = { observedAt })
        val observer = observer(graph)

        observer.onExecutionResult(result(finalState = TaskState.FAILED))

        val worker = assertNotNull(graph.node(workerNodeId))
        assertEquals(HealthState.UNHEALTHY, worker.state)
        assertEquals(1, worker.totalFailures)
    }

    private fun observer(graph: HealthGraph): HealthTaskExecutionObserver =
        HealthTaskExecutionObserver(
            workerNodeId = workerNodeId,
            graph = graph,
            now = { observedAt },
        )

    private fun result(
        finalState: TaskState,
        failures: List<RuntimeFailure> = emptyList(),
        fieldShadow: FieldShadowExecution? = null,
    ): CognitiveTaskExecutionResult = CognitiveTaskExecutionResult(
        taskId = TaskId("task-1"),
        photonId = photonId,
        finalState = finalState,
        influences = emptyList(),
        failures = failures,
        fieldShadow = fieldShadow,
    )
}
