package app.lifeos.core.runtime.health

import app.lifeos.core.runtime.RuntimeFailure
import app.lifeos.core.runtime.RuntimeFailureCategory
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class HealthGraphTest {
    private val nodeId = HealthNodeId("worker:cognitive-0")
    private val t0 = Instant.parse("2026-09-07T18:00:00Z")

    @Test
    fun repeatedRecoverableFailuresEscalateToUnhealthy() = runTest {
        val graph = HealthGraph(
            unhealthyAfterConsecutiveFailures = 3,
            now = { t0 },
        )
        graph.register(nodeId, HealthScope.WORKER)
        val failure = RuntimeFailure(
            category = RuntimeFailureCategory.UNKNOWN,
            source = "cognitive-worker",
            message = "temporary worker failure",
        )

        assertEquals(HealthState.DEGRADED, graph.recordFailure(nodeId, failure).state)
        assertEquals(HealthState.DEGRADED, graph.recordFailure(nodeId, failure).state)
        val third = graph.recordFailure(nodeId, failure)

        assertEquals(HealthState.UNHEALTHY, third.state)
        assertEquals(3, third.consecutiveFailures)
        assertEquals(3, third.totalFailures)
    }

    @Test
    fun healthyObservationResetsConsecutiveFailureCount() = runTest {
        val graph = HealthGraph(unhealthyAfterConsecutiveFailures = 2, now = { t0 })
        graph.register(nodeId, HealthScope.WORKER)
        val failure = RuntimeFailure(
            category = RuntimeFailureCategory.TIMEOUT,
            source = "cognitive-worker",
            message = "timeout",
        )

        graph.recordFailure(nodeId, failure)
        val healthy = graph.recordHealthy(nodeId, source = "worker-heartbeat")

        assertEquals(HealthState.HEALTHY, healthy.state)
        assertEquals(0, healthy.consecutiveFailures)
        assertEquals(1, healthy.totalFailures)
        assertEquals(t0, healthy.lastHealthyAt)
    }

    @Test
    fun snapshotReportsWorstNodeStateDeterministically() = runTest {
        val graph = HealthGraph(now = { t0 })
        val runtime = HealthNodeId("runtime")
        val storage = HealthNodeId("storage:photon")
        graph.register(runtime, HealthScope.RUNTIME)
        graph.register(storage, HealthScope.STORAGE_ENGINE)
        graph.recordHealthy(runtime, source = "runtime")
        graph.recordFailure(
            storage,
            RuntimeFailure(
                category = RuntimeFailureCategory.STORAGE,
                source = "photon-repository",
                message = "io failed",
                recoverable = false,
            ),
        )

        val snapshot = graph.snapshot()

        assertEquals(HealthState.UNHEALTHY, snapshot.overallState)
        assertEquals(listOf("runtime", "storage:photon"), snapshot.nodes.map { it.id.value })
    }

    @Test
    fun recentHistoryIsBoundedAndNewestFirst() = runTest {
        val graph = HealthGraph(maxObservationsPerNode = 2, now = { t0 })
        graph.register(nodeId, HealthScope.WORKER)
        graph.recordHealthy(nodeId, source = "one", message = "one")
        graph.recordHealthy(nodeId, source = "two", message = "two")
        graph.recordHealthy(nodeId, source = "three", message = "three")

        val recent = graph.recent(nodeId, limit = 10)

        assertEquals(listOf("three", "two"), recent.map { it.message })
        assertNotNull(graph.node(nodeId))
    }
}
