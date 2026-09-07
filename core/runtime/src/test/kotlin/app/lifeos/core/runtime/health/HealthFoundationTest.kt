package app.lifeos.core.runtime.health

import app.lifeos.core.model.Photon
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.ForceField
import app.lifeos.core.runtime.InfluenceExecutor
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class HealthFoundationTest {
    @Test fun circuitOpensAllowsOneProbeAndClosesOnSuccess() {
        var time = 0L
        val breaker = CircuitBreaker(2, 10) { time }
        val stale = assertNotNull(breaker.acquire())
        breaker.failure(stale)
        breaker.failure(assertNotNull(breaker.acquire()))
        assertEquals(CircuitBreaker.State.OPEN, breaker.state)
        assertNull(breaker.acquire())
        assertFalse(breaker.success(stale))
        time = 10
        val probe = assertNotNull(breaker.acquire())
        assertNull(breaker.acquire())
        assertTrue(breaker.success(probe))
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state)
        assertNotNull(breaker.acquire())
    }

    @Test fun cancelledProbeDoesNotWedgeHalfOpen() {
        var time = 0L
        val breaker = CircuitBreaker(1, 10) { time }
        breaker.failure(assertNotNull(breaker.acquire()))
        time = 10
        breaker.cancel(assertNotNull(breaker.acquire()))
        assertEquals(CircuitBreaker.State.OPEN, breaker.state)
        time = 20
        assertNotNull(breaker.acquire())
    }

    @Test fun storageFailureRecoversWithoutDegradingUnrelatedNodes() = runTest {
        var time = 0L
        val health = RecoveryCoordinator(breakerFactory = { CircuitBreaker(1, 10) { time } })
        assertFailsWith<IOException> { health.execute(HealthNodes.TaskStore) { throw IOException("private data") } }
        assertEquals(HealthState.UNHEALTHY, health.graph.states.value["TaskStore"])
        assertEquals(HealthState.UNKNOWN, health.graph.states.value["Kernel"])
        assertFalse(health.safeMode.active)
        assertFalse(health.graph.observations.value.any { "private data" in it.reason })
        assertFailsWith<ComponentUnavailable> { health.execute(HealthNodes.TaskStore) { fail("must not run") } }
        time = 10
        health.execute(HealthNodes.TaskStore) { Unit }
        assertEquals(HealthState.HEALTHY, health.graph.states.value["TaskStore"])
        assertTrue(health.graph.observations.value.any { it.state == HealthState.RECOVERING })
    }

    @Test fun securityFailureQuarantinesOnlyItsNode() = runTest {
        val health = RecoveryCoordinator()
        val node = HealthNode("test-field")
        assertFailsWith<SecurityException> { health.execute(node) { throw SecurityException() } }
        assertTrue(health.quarantine.contains(node))
        assertFailsWith<ComponentUnavailable> { health.execute(node) { fail("quarantined") } }
        health.execute(HealthNodes.PhotonStore) { Unit }
        assertFalse(health.safeMode.active)
        assertEquals(HealthState.HEALTHY, health.graph.states.value["PhotonStore"])
    }

    @Test fun criticalInvariantEntersSafeModeWithoutBlockingVaultRead() = runTest {
        val health = RecoveryCoordinator()
        assertFailsWith<InvariantViolation> {
            health.execute(HealthNodes.TaskStore) { InvariantChecker().requireValid(false) }
        }
        assertTrue(health.safeMode.active)
        val content = health.execute(HealthNodes.PhotonStore) { "preserved" }
        assertEquals("preserved", content)
        assertEquals(HealthState.QUARANTINED, health.graph.states.value["TaskStore"])
        assertTrue(health.graph.observations.value.isNotEmpty())
    }

    @Test fun cancellationDoesNotBecomeHealthFailure() = runTest {
        val health = RecoveryCoordinator()
        assertFailsWith<CancellationException> {
            health.execute(HealthNodes.Worker) { throw CancellationException("stop") }
        }
        assertEquals(HealthState.UNKNOWN, health.graph.states.value["Worker"])
    }

    @Test fun repeatedFieldFailureIsIsolatedAndNotCheckpointedAsSuccess() = runTest {
        val health = RecoveryCoordinator()
        val executor = InfluenceExecutor(health)
        var brokenCalls = 0
        var healthyCalls = 0
        val fields = listOf(ForceField { brokenCalls++; error("broken") }, ForceField { healthyCalls++; null })
        repeat(5) {
            val completed = mutableListOf<Int>()
            val result = executor.execute(photon(), fields, onFieldSuccess = { completed += it })
            assertEquals(listOf(1), completed)
            assertEquals(1, result.failures.size)
        }
        assertEquals(3, brokenCalls)
        assertEquals(5, healthyCalls)
        assertFalse(health.safeMode.active)
        assertEquals(HealthState.UNKNOWN, health.graph.states.value["Runtime"])
    }

    @Test fun hangingCooperativeFieldTimesOutAndNextFieldStillRuns() = runTest {
        val health = RecoveryCoordinator()
        var reached = false
        val result = InfluenceExecutor(health, 50).execute(photon(), listOf(
            ForceField { awaitCancellation() }, ForceField { reached = true; null },
        ))
        assertTrue(reached)
        assertEquals(1, result.failures.size)
        assertTrue(health.graph.observations.value.any { it.reason == "TIMEOUT" })
    }

    @Test fun bootLoopSurvivesGuardRecreationAndStableBootResetsCount() {
        val store = object : BootAttemptStore {
            var count = 0
            override fun read() = count
            override fun write(attempts: Int) { count = attempts }
        }
        assertFalse(BootLoopGuard(store).begin())
        assertFalse(BootLoopGuard(store).begin())
        assertTrue(BootLoopGuard(store).begin())
        assertTrue(BootLoopGuard(store).begin())
        BootLoopGuard(store).markStable()
        assertFalse(BootLoopGuard(store).begin())
    }

    @Test fun diagnosticHistoryIsBounded() {
        val graph = HealthGraph()
        repeat(150) { graph.record(HealthNodes.Worker, HealthState.DEGRADED, "execution") }
        assertEquals(100, graph.observations.value.size)
        assertEquals(150L, graph.observations.value.last().sequence)
    }

    private fun photon() = Photon(content = "test", provenance = Provenance("test", "user"))
}
