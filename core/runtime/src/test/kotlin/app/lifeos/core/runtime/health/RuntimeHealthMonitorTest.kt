package app.lifeos.core.runtime.health

import app.lifeos.core.model.Photon
import app.lifeos.core.runtime.LifeOsRuntime
import app.lifeos.core.runtime.RuntimeFailure
import app.lifeos.core.runtime.RuntimeFailureCategory
import app.lifeos.core.runtime.RuntimeState
import app.lifeos.core.runtime.RuntimeStatus
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeHealthMonitorTest {
    private val observedAt = Instant.parse("2026-09-08T03:46:00Z")
    private val nodeId = HealthNodeId("runtime:test")

    @Test
    fun runningProjectsHealthyRuntime() = runTest {
        val runtime = FakeRuntime(RuntimeState(status = RuntimeStatus.RUNNING))
        val graph = HealthGraph(now = { observedAt })
        val monitor = RuntimeHealthMonitor(
            scope = backgroundScope,
            runtime = runtime,
            graph = graph,
            nodeId = nodeId,
            now = { observedAt },
        )

        monitor.start()
        runCurrent()

        val node = assertNotNull(graph.node(nodeId))
        assertEquals(HealthScope.RUNTIME, node.scope)
        assertEquals(HealthState.HEALTHY, node.state)
        monitor.stop()
    }

    @Test
    fun degradedProjectsStructuredRecoverableObservation() = runTest {
        val runtime = FakeRuntime(RuntimeState(status = RuntimeStatus.CREATED))
        val graph = HealthGraph(now = { observedAt })
        val monitor = RuntimeHealthMonitor(
            scope = backgroundScope,
            runtime = runtime,
            graph = graph,
            nodeId = nodeId,
            now = { observedAt },
        )
        monitor.start()
        runCurrent()

        runtime.emit(
            RuntimeState(
                status = RuntimeStatus.DEGRADED,
                lastFailure = RuntimeFailure(
                    category = RuntimeFailureCategory.STORAGE,
                    source = "durable-runtime",
                    message = "store temporarily unavailable",
                    recoverable = true,
                ),
            ),
        )
        runCurrent()

        val node = assertNotNull(graph.node(nodeId))
        assertEquals(HealthState.DEGRADED, node.state)
        assertEquals(1, node.consecutiveFailures)
        monitor.stop()
    }

    @Test
    fun failedProjectsUnhealthyWhenFailureIsNonRecoverable() = runTest {
        val runtime = FakeRuntime(RuntimeState(status = RuntimeStatus.CREATED))
        val graph = HealthGraph(now = { observedAt })
        val monitor = RuntimeHealthMonitor(
            scope = backgroundScope,
            runtime = runtime,
            graph = graph,
            nodeId = nodeId,
            now = { observedAt },
        )
        monitor.start()
        runCurrent()

        runtime.emit(
            RuntimeState(
                status = RuntimeStatus.FAILED,
                lastFailure = RuntimeFailure(
                    category = RuntimeFailureCategory.INVARIANT,
                    source = "durable-runtime",
                    message = "runtime invariant broken",
                    recoverable = false,
                ),
            ),
        )
        runCurrent()

        val node = assertNotNull(graph.node(nodeId))
        assertEquals(HealthState.UNHEALTHY, node.state)
        monitor.stop()
    }

    @Test
    fun cleanStopResetsConsecutiveFailureProjection() = runTest {
        val runtime = FakeRuntime(
            RuntimeState(
                status = RuntimeStatus.DEGRADED,
                lastFailure = RuntimeFailure(
                    category = RuntimeFailureCategory.TIMEOUT,
                    source = "durable-runtime",
                    message = "timeout",
                    recoverable = true,
                ),
            ),
        )
        val graph = HealthGraph(now = { observedAt })
        val monitor = RuntimeHealthMonitor(
            scope = backgroundScope,
            runtime = runtime,
            graph = graph,
            nodeId = nodeId,
            now = { observedAt },
        )
        monitor.start()
        runCurrent()
        runtime.emit(RuntimeState(status = RuntimeStatus.STOPPED))
        runCurrent()

        val node = assertNotNull(graph.node(nodeId))
        assertEquals(HealthState.HEALTHY, node.state)
        assertEquals(0, node.consecutiveFailures)
        monitor.stop()
    }

    private class FakeRuntime(initial: RuntimeState) : LifeOsRuntime {
        private val mutableState = MutableStateFlow(initial)
        override val state: StateFlow<RuntimeState> = mutableState

        fun emit(next: RuntimeState) {
            mutableState.value = next
        }

        override fun start() = Unit
        override fun stop() = Unit
        override suspend fun ingest(photon: Photon) = Unit
    }
}
