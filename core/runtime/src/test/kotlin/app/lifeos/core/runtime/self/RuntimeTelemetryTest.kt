package app.lifeos.core.runtime.self

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuntimeTelemetryTest {
    @Test
    fun heapHeadroomIsBounded() {
        val telemetry = snapshot(heapUsedBytes = 25, heapMaxBytes = 100)

        assertEquals(0.75, telemetry.heapHeadroom())
        assertTrue(telemetry.heapHeadroom() in 0.0..1.0)
    }

    @Test
    fun trafficDeltaPreservesUnknownAndRejectsCounterReset() {
        val previous = snapshot(elapsedRealtimeNanos = 10, uidRxBytes = 100, uidTxBytes = null)
        val next = snapshot(elapsedRealtimeNanos = 20, uidRxBytes = 140, uidTxBytes = 10)

        val delta = requireNotNull(next.trafficDelta(previous))
        assertEquals(10L, delta.elapsedRealtimeNanos)
        assertEquals(40L, delta.rxBytes)
        assertNull(delta.txBytes)

        val reset = next.copy(elapsedRealtimeNanos = 30, uidRxBytes = 50)
        assertNull(requireNotNull(reset.trafficDelta(next)).rxBytes)
    }

    @Test
    fun telemetryChangesStateFingerprintButNeverAuthorityFingerprint() {
        val first = selfSnapshot(snapshot(processCpuTimeMillis = 10))
        val second = selfSnapshot(snapshot(processCpuTimeMillis = 20))

        assertEquals(first.authorityFingerprint, second.authorityFingerprint)
        assertNotEquals(first.stateFingerprint, second.stateFingerprint)
    }

    private fun snapshot(
        elapsedRealtimeNanos: Long = 100,
        processCpuTimeMillis: Long = 10,
        heapUsedBytes: Long = 25,
        heapMaxBytes: Long = 100,
        uidRxBytes: Long? = 100,
        uidTxBytes: Long? = 200,
    ) = RuntimeTelemetrySnapshot(
        observedAt = Instant.parse("2026-09-19T12:00:00Z"),
        elapsedRealtimeNanos = elapsedRealtimeNanos,
        processCpuTimeMillis = processCpuTimeMillis,
        heapUsedBytes = heapUsedBytes,
        heapMaxBytes = heapMaxBytes,
        nativeHeapAllocatedBytes = 12,
        activeThreadCount = 4,
        uidRxBytes = uidRxBytes,
        uidTxBytes = uidTxBytes,
    )

    private fun selfSnapshot(telemetry: RuntimeTelemetrySnapshot) = LifeOsSelfStateSnapshot(
        capturedAt = Instant.parse("2026-09-19T12:00:00Z"),
        photon = SelfPhotonState(1, 1, 0, "index"),
        memory = SelfMemoryState(1, 1, 0, "memory"),
        world = SelfWorldState(1, "world", 1, "eq-v1", "eq", "boot", "boot-fp", "cog"),
        runtime = SelfRuntimeState(
            topologyFingerprint = "topology",
            registeredSubsystems = setOf("world"),
            operationalSubsystems = setOf("world"),
            degradedSubsystems = emptySet(),
            unavailableSubsystems = emptySet(),
            unboundSubsystems = emptySet(),
            telemetry = telemetry,
        ),
        resource = SelfResourceState("hardware", 1.0, 1.0, 1.0, 1.0, 1.0),
        health = SelfHealthState(1, 0, 0, 0, 0, 0),
        recovery = SelfRecoveryState(emptySet(), "recovery"),
        tools = SelfToolState(0, 0, 0, 0, 0),
        liveSources = SelfLiveSourceState(0, 0, 0, 0, "sources"),
    )
}
