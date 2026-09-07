package app.lifeos.core.runtime.health

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CircuitBreakerTest {
    private val nodeId = HealthNodeId("worker:cognitive-0")
    private val t0 = Instant.parse("2026-09-07T18:00:00Z")

    @Test
    fun opensAfterThresholdAndAllowsSingleHalfOpenProbe() = runTest {
        val breaker = CircuitBreaker(
            CircuitBreakerPolicy(
                failureThreshold = 2,
                openDuration = Duration.ofSeconds(10),
            )
        )

        val first = assertIs<CircuitAcquireResult.Granted>(breaker.acquire(nodeId, t0)).permit
        assertTrue(breaker.onFailure(first, t0))
        val second = assertIs<CircuitAcquireResult.Granted>(breaker.acquire(nodeId, t0)).permit
        assertTrue(breaker.onFailure(second, t0))

        val open = assertIs<CircuitAcquireResult.Rejected>(
            breaker.acquire(nodeId, t0.plusSeconds(9))
        )
        assertEquals(CircuitState.OPEN, open.state)
        assertEquals(t0.plusSeconds(10), open.retryAt)

        val results = listOf(
            async { breaker.acquire(nodeId, t0.plusSeconds(10)) },
            async { breaker.acquire(nodeId, t0.plusSeconds(10)) },
        ).awaitAll()

        assertEquals(1, results.count { it is CircuitAcquireResult.Granted })
        assertEquals(1, results.count { it is CircuitAcquireResult.Rejected })
        val probe = (results.single { it is CircuitAcquireResult.Granted } as CircuitAcquireResult.Granted).permit
        assertTrue(probe.probe)
        assertTrue(breaker.onSuccess(probe))
        assertEquals(CircuitState.CLOSED, breaker.snapshot(nodeId).state)
    }

    @Test
    fun stalePermitCannotCloseCircuitAfterAnotherCallerOpenedIt() = runTest {
        val breaker = CircuitBreaker(
            CircuitBreakerPolicy(
                failureThreshold = 1,
                openDuration = Duration.ofSeconds(30),
            )
        )

        val stale = assertIs<CircuitAcquireResult.Granted>(breaker.acquire(nodeId, t0)).permit
        val opener = assertIs<CircuitAcquireResult.Granted>(breaker.acquire(nodeId, t0)).permit
        assertTrue(breaker.onFailure(opener, t0))

        assertFalse(breaker.onSuccess(stale))
        assertEquals(CircuitState.OPEN, breaker.snapshot(nodeId).state)
    }

    @Test
    fun failedHalfOpenProbeReopensCircuitWithFreshWindow() = runTest {
        val breaker = CircuitBreaker(
            CircuitBreakerPolicy(
                failureThreshold = 1,
                openDuration = Duration.ofSeconds(5),
            )
        )
        val initial = assertIs<CircuitAcquireResult.Granted>(breaker.acquire(nodeId, t0)).permit
        breaker.onFailure(initial, t0)

        val probeTime = t0.plusSeconds(5)
        val probe = assertIs<CircuitAcquireResult.Granted>(breaker.acquire(nodeId, probeTime)).permit
        assertTrue(probe.probe)
        assertTrue(breaker.onFailure(probe, probeTime))

        val snapshot = breaker.snapshot(nodeId)
        assertEquals(CircuitState.OPEN, snapshot.state)
        assertEquals(t0.plusSeconds(10), snapshot.openedUntil)
    }
}
