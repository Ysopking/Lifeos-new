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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RuntimeHealthMonitorTest {
    private val t0 = Instant.parse("2026-09-07T18:00:00Z")

    @Test
    fun runningThenFailedUpdatesRuntimeHealthNode() = runTest {
        val runtime = FakeRuntime()
        val graph = HealthGraph(now = { t0 })
        val monitor = RuntimeHealthMonitor(
            scope = this,
            runtime = runtime,
            graph = graph,
            now = { t0 },
        )
        monitor.start()

        runtime.mutableState.value = RuntimeState(status = RuntimeStatus.RUNNING)
        advanceUntilIdle()
        assertEquals(HealthState.HEALTHY, assertNotNull(graph.node(HealthNodeId("runtime"))).state)

        runtime.mutableState.value = RuntimeState(
            status = RuntimeStatus.FAILED,
            lastFailure = RuntimeFailure(
                category = RuntimeFailureCategory.INVARIANT,
                source = "durable-runtime",
                message = "runtime invariant failed",
                recoverable = false,
            ),
        )
        advanceUntilIdle()

        val node = assertNotNull(graph.node(HealthNodeId("runtime")))
        assertEquals(HealthScope.RUNTIME, node.scope)
        assertEquals(HealthState.UNHEALTHY, node.state)
        monitor.stop()
    }

    private class FakeRuntime : LifeOsRuntime {
        val mutableState = MutableStateFlow(RuntimeState())
        override val state: StateFlow<RuntimeState> = mutableState

        override fun start() = Unit
        override fun stop() = Unit
        override suspend fun ingest(photon: Photon) = Unit
    }
}
