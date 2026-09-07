package app.lifeos.core.runtime.health

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HealthGateTest {
    private val nodeId = HealthNodeId("worker:cognitive-0")
    private val t0 = Instant.parse("2026-09-07T18:00:00Z")

    @Test
    fun quarantineBlocksBeforeCircuitAndExpiresDeterministically() = runTest {
        val quarantine = QuarantineRegistry()
        val breaker = CircuitBreaker(
            CircuitBreakerPolicy(
                failureThreshold = 1,
                openDuration = Duration.ofSeconds(30),
            )
        )
        val gate = HealthGate(breaker, quarantine)

        quarantine.quarantine(
            QuarantineEntry(
                nodeId = nodeId,
                source = "health-test",
                reason = "isolated",
                quarantinedAt = t0,
                until = t0.plusSeconds(10),
            )
        )

        val blocked = assertIs<HealthGateResult.BlockedByQuarantine>(
            gate.acquire(nodeId, t0.plusSeconds(5))
        )
        assertEquals("isolated", blocked.entry.reason)

        assertNull(quarantine.active(nodeId, t0.plusSeconds(10)))
        val granted = assertIs<HealthGateResult.Granted>(
            gate.acquire(nodeId, t0.plusSeconds(10))
        )
        assertTrue(gate.onSuccess(granted.permit))
    }

    @Test
    fun circuitFailureBlocksUntilProbeWindow() = runTest {
        val quarantine = QuarantineRegistry()
        val breaker = CircuitBreaker(
            CircuitBreakerPolicy(
                failureThreshold = 1,
                openDuration = Duration.ofSeconds(5),
            )
        )
        val gate = HealthGate(breaker, quarantine)

        val first = assertIs<HealthGateResult.Granted>(gate.acquire(nodeId, t0))
        assertTrue(gate.onFailure(first.permit, t0))

        val blocked = assertIs<HealthGateResult.BlockedByCircuit>(
            gate.acquire(nodeId, t0.plusSeconds(1))
        )
        assertEquals(CircuitState.OPEN, blocked.state)
        assertEquals(t0.plusSeconds(5), blocked.retryAt)

        val probe = assertIs<HealthGateResult.Granted>(
            gate.acquire(nodeId, t0.plusSeconds(5))
        )
        assertTrue(probe.permit.probe)
    }

    @Test
    fun quarantineSnapshotIsSortedAndReleaseIsExplicit() = runTest {
        val registry = QuarantineRegistry()
        val b = HealthNodeId("b")
        val a = HealthNodeId("a")
        registry.quarantine(QuarantineEntry(b, "test", "b", t0))
        registry.quarantine(QuarantineEntry(a, "test", "a", t0))

        assertEquals(listOf("a", "b"), registry.snapshot(t0).map { it.nodeId.value })
        assertEquals(a, registry.release(a)?.nodeId)
        assertNull(registry.active(a, t0))
    }
}
