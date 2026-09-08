package app.lifeos.core.runtime.health

import app.lifeos.core.model.Photon
import app.lifeos.core.runtime.LifeOsRuntime
import app.lifeos.core.runtime.RuntimeFailure
import app.lifeos.core.runtime.RuntimeFailureCategory
import app.lifeos.core.runtime.RuntimeState
import app.lifeos.core.runtime.RuntimeStatus
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RuntimeHealthMonitorTest {
    private val observedAt = Instant.parse("2026-09-08T09:00:00Z")
    private val nodeId = HealthNodeId("runtime")

    @Test
    fun projectsRunningDegradedAndTerminalFailedStates() = runTest {
        val runtime = FakeRuntime()
        val graph = HealthGraph(now = { observedAt })
        val monitor = RuntimeHealthMonitor(
            scope = backgroundScope,
            runtime = runtime,
            graph = graph,
            now = { observedAt },
        )
        monitor.start()
        runCurrent()

        runtime.emit(RuntimeState(status = RuntimeStatus.RUNNING))
        runCurrent()
        assertEquals(HealthState.HEALTHY, graph.node(nodeId)?.state)

        runtime.emit(
            RuntimeState(
                status = RuntimeStatus.DEGRADED,
                lastFailure = RuntimeFailure(
                    category = RuntimeFailureCategory.TIMEOUT,
                    source = "durable-runtime",
                    message = "worker response slow",
                    recoverable = true,
                ),
            ),
        )
        runCurrent()
        val degraded = assertNotNull(graph.node(nodeId))
        assertEquals(HealthScope.RUNTIME, degraded.scope)
        assertEquals(HealthState.DEGRADED, degraded.state)
        assertEquals(1, degraded.consecutiveFailures)

        runtime.emit(
            RuntimeState(
                status = RuntimeStatus.FAILED,
                lastFailure = RuntimeFailure(
                    category = RuntimeFailureCategory.TIMEOUT,
                    source = "durable-runtime",
                    message = "runtime stopped after timeout",
                    recoverable = true,
                ),
            ),
        )
        runCurrent()

        val failed = assertNotNull(graph.node(nodeId))
        assertEquals(HealthState.UNHEALTHY, failed.state)
        assertEquals(2, failed.consecutiveFailures)
        assertEquals(2, failed.totalFailures)
        assertEquals("runtime stopped after timeout", failed.lastMessage)

        monitor.stop()
    }

    @Test
    fun cleanStopReturnsRuntimeNodeToHealthy() = runTest {
        val runtime = FakeRuntime()
        val graph = HealthGraph(now = { observedAt })
        val monitor = RuntimeHealthMonitor(
            scope = backgroundScope,
            runtime = runtime,
            graph = graph,
            now = { observedAt },
        )
        monitor.start()
        runCurrent()

        runtime.emit(RuntimeState(status = RuntimeStatus.RUNNING))
        runCurrent()
        runtime.emit(RuntimeState(status = RuntimeStatus.STOPPED))
        runCurrent()

        val stopped = assertNotNull(graph.node(nodeId))
        assertEquals(HealthState.HEALTHY, stopped.state)
        assertEquals(0, stopped.consecutiveFailures)
        assertEquals("Runtime stopped cleanly", stopped.lastMessage)

        monitor.stop()
    }

    private class FakeRuntime : LifeOsRuntime {
        private val mutableState = MutableStateFlow(RuntimeState())
        override val state: StateFlow<RuntimeState> = mutableState.asStateFlow()

        fun emit(state: RuntimeState) {
            mutableState.value = state
        }

        override fun start() = Unit

        override fun stop() = Unit

        override suspend fun ingest(photon: Photon) = Unit
    }
}
