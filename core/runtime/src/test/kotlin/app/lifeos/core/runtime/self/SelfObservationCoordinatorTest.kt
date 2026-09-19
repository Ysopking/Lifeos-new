package app.lifeos.core.runtime.self

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SelfObservationCoordinatorTest {
    @Test
    fun identicalStableStateIsSuppressedAfterBandSettles() = runTest {
        val result = result()
        val coordinator = SelfObservationCoordinator(SelfObservationCapture { result })

        val first = coordinator.refresh(SelfObservationTrigger.STARTUP)
        val second = coordinator.refresh(SelfObservationTrigger.TIMER)
        val third = coordinator.refresh(SelfObservationTrigger.TIMER)

        assertEquals(SelfObservationBand.ACTIVE, first.band)
        assertEquals(SelfObservationBand.STABLE, second.band)
        assertTrue(second.emitted)
        assertEquals(SelfObservationBand.STABLE, third.band)
        assertFalse(third.emitted)
        assertEquals(second.materialFingerprint, third.materialFingerprint)
    }

    @Test
    fun criticalAndRecoveryStatesUseFastBands() = runTest {
        var current = result(
            health = SelfHealthState(0, 0, 1, 0, 0, 0)
        )
        val coordinator = SelfObservationCoordinator(SelfObservationCapture { current })

        val critical = coordinator.refresh(SelfObservationTrigger.HEALTH_TRANSITION)
        assertEquals(SelfObservationBand.CRITICAL, critical.band)
        assertEquals(Duration.ofSeconds(1), coordinator.nextInterval(critical.band))

        current = result(
            health = SelfHealthState(0, 0, 0, 1, 0, 0),
            recovery = SelfRecoveryState(setOf("repair-1"), "repair-fp"),
        )
        val recovering = coordinator.refresh(SelfObservationTrigger.RECOVERY_TRANSITION)
        assertEquals(SelfObservationBand.RECOVERING, recovering.band)
        assertEquals(Duration.ofSeconds(1), coordinator.nextInterval(recovering.band))
    }

    @Test
    fun cumulativeCpuTimeAloneDoesNotCreateMaterialStateChurn() = runTest {
        var cpu = 10L
        val coordinator = SelfObservationCoordinator(
            SelfObservationCapture {
                result(
                    telemetry = RuntimeTelemetrySnapshot(
                        observedAt = Instant.parse("2026-09-19T12:00:00Z").plusSeconds(cpu),
                        elapsedRealtimeNanos = cpu * 1_000L,
                        processCpuTimeMillis = cpu,
                        heapUsedBytes = 25,
                        heapMaxBytes = 100,
                        nativeHeapAllocatedBytes = 10,
                        activeThreadCount = 4,
                        uidRxBytes = cpu * 100,
                        uidTxBytes = cpu * 50,
                    )
                )
            }
        )

        val first = coordinator.refresh(SelfObservationTrigger.STARTUP)
        coordinator.refresh(SelfObservationTrigger.TIMER)
        cpu = 20L
        val next = coordinator.refresh(SelfObservationTrigger.TIMER)

        assertEquals(first.materialFingerprint, next.materialFingerprint)
        assertFalse(next.emitted)
    }

    @Test
    fun explicitUiRefreshPublishesEvenWhenMaterialStateIsIdentical() = runTest {
        val result = result()
        val coordinator = SelfObservationCoordinator(SelfObservationCapture { result })
        coordinator.refresh(SelfObservationTrigger.STARTUP)
        coordinator.refresh(SelfObservationTrigger.TIMER)

        val explicit = coordinator.refresh(SelfObservationTrigger.EXPLICIT_UI_REFRESH)

        assertTrue(explicit.emitted)
        assertEquals(SelfObservationTrigger.EXPLICIT_UI_REFRESH, coordinator.observe().value?.trigger)
    }

    private fun result(
        health: SelfHealthState = SelfHealthState(1, 0, 0, 0, 0, 0),
        recovery: SelfRecoveryState = SelfRecoveryState(emptySet(), "recovery"),
        telemetry: RuntimeTelemetrySnapshot? = null,
    ): SelfStateProjectionResult {
        val snapshot = LifeOsSelfStateSnapshot(
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
            resource = SelfResourceState("hardware", 0.8, 0.8, 1.0, 0.9, 0.9),
            health = health,
            recovery = recovery,
            tools = SelfToolState(0, 0, 0, 0, 0),
            liveSources = SelfLiveSourceState(0, 0, 0, 0, "sources"),
        )
        return SelfStateProjectionResult(snapshot, emptyList())
    }
}
