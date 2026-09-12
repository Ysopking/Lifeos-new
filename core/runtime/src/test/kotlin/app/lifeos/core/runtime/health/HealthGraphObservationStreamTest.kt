package app.lifeos.core.runtime.health

import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthGraphObservationStreamTest {
    @Test
    fun `record emits the same observation after graph state update`() = runTest {
        val graph = HealthGraph()
        val nodeId = HealthNodeId("runtime")
        graph.register(nodeId, HealthScope.RUNTIME)
        val received = async { graph.observations.first() }
        runCurrent()
        val observation = HealthObservation(
            nodeId = nodeId,
            state = HealthState.UNHEALTHY,
            observedAt = Instant.parse("2026-09-11T13:30:00Z"),
            source = "test",
            message = "failed",
        )
        graph.record(observation)
        assertEquals(observation, received.await())
        assertEquals(HealthState.UNHEALTHY, graph.node(nodeId)?.state)
    }
}
