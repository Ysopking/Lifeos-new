package app.lifeos.core.runtime.resource

import app.lifeos.core.field.world.WorldNodeKind
import app.lifeos.core.field.world.WorldSignalDimension
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HardwareAdaptiveResourceIntelligenceTest {
    private val optimizer = HardwareAdaptiveResourceOptimizer()
    private val hardQuota = ResourceBudgetQuota(
        elapsedMillis = 10_000,
        workUnits = 100,
        memoryBytes = 1_000,
        ioBytes = 1_000,
        networkBytes = 1_000,
        candidates = 10,
    )

    @Test
    fun criticalThermalAndLowBatteryThrottleWithoutExceedingHardQuota() {
        val hardware = HardwareStateSnapshot(
            observedAt = Instant.parse("2026-09-11T12:00:00Z"),
            availableProcessors = 8,
            batteryFraction = 0.08,
            charging = false,
            thermalState = HardwareThermalState.CRITICAL,
            availableMemoryBytes = 250,
            totalMemoryBytes = 1_000,
            availableStorageBytes = 500,
            totalStorageBytes = 1_000,
        )
        val requested = hardQuota.asRequestedUsage()
        val plan = optimizer.plan(hardQuota, requested, hardware)

        assertEquals(HardwareBudgetMode.THROTTLED, plan.mode)
        assertTrue(plan.effectiveQuota.workUnits < hardQuota.workUnits)
        assertTrue(plan.effectiveQuota.memoryBytes < hardQuota.memoryBytes)
        assertTrue(plan.effectiveQuota.networkBytes < hardQuota.networkBytes)
        assertTrue(plan.effectiveQuota.candidates < hardQuota.candidates)
        assertTrue(plan.effectiveQuota.workUnits <= hardQuota.workUnits)
        assertTrue(plan.recommendedReservation.isWithin(requested))
    }

    @Test
    fun healthyChargingDeviceCanUseLargeEnvelopeButNeverExceedsHardQuota() {
        val hardware = HardwareStateSnapshot(
            observedAt = Instant.parse("2026-09-11T12:00:00Z"),
            availableProcessors = 8,
            batteryFraction = 0.40,
            charging = true,
            thermalState = HardwareThermalState.NOMINAL,
            cpuLoadFraction = 0.05,
            availableMemoryBytes = 900,
            totalMemoryBytes = 1_000,
            availableStorageBytes = 900,
            totalStorageBytes = 1_000,
        )
        val requested = ResourceBudgetUsage(
            elapsedMillis = 5_000,
            workUnits = 70,
            memoryBytes = 500,
            ioBytes = 500,
            networkBytes = 500,
            candidates = 5,
        )
        val plan = optimizer.plan(
            hardQuota = hardQuota,
            requested = requested,
            hardware = hardware,
            priority = HardwareWorkPriority.CRITICAL,
        )

        assertEquals(HardwareBudgetMode.NORMAL, plan.mode)
        assertTrue(plan.requestedFits)
        assertEquals(requested, plan.recommendedReservation)
        assertTrue(plan.effectiveQuota.workUnits <= hardQuota.workUnits)
        assertTrue(plan.effectiveQuota.memoryBytes <= hardQuota.memoryBytes)
        assertTrue(plan.effectiveQuota.ioBytes <= hardQuota.ioBytes)
        assertTrue(plan.effectiveQuota.networkBytes <= hardQuota.networkBytes)
        assertTrue(plan.effectiveQuota.candidates <= hardQuota.candidates)
    }

    @Test
    fun emergencyThermalStateSuspendsHeavyWork() {
        val hardware = HardwareStateSnapshot(
            observedAt = Instant.parse("2026-09-11T12:00:00Z"),
            availableProcessors = 8,
            batteryFraction = 0.80,
            charging = true,
            thermalState = HardwareThermalState.EMERGENCY,
        )
        val plan = optimizer.plan(
            hardQuota = hardQuota,
            requested = ResourceBudgetUsage(workUnits = 1),
            hardware = hardware,
        )

        assertEquals(HardwareBudgetMode.SUSPENDED, plan.mode)
        assertEquals(0L, plan.effectiveQuota.workUnits)
        assertEquals(0L, plan.effectiveQuota.memoryBytes)
        assertTrue(plan.recommendedReservation.isZero())
    }

    @Test
    fun hardwareSnapshotBecomesTypedWorldFormulaInputWithProvenance() {
        val hardware = HardwareStateSnapshot(
            observedAt = Instant.parse("2026-09-11T12:00:00Z"),
            availableProcessors = 6,
            batteryFraction = 0.70,
            charging = false,
            thermalState = HardwareThermalState.FAIR,
            availableMemoryBytes = 700,
            totalMemoryBytes = 1_000,
            availableStorageBytes = 800,
            totalStorageBytes = 1_000,
        )
        val input = hardware.toWorldFormulaInput()

        assertEquals(WorldNodeKind.HEALTH, input.target.kind)
        assertEquals(hardware.fingerprint(), input.sourceSnapshotFingerprint)
        assertNotNull(input.vector[WorldSignalDimension.HEALTH_STABILITY])
        assertNotNull(input.vector[WorldSignalDimension.CAPABILITY_READINESS])
        assertNotNull(input.vector[WorldSignalDimension.TEMPORAL_FRESHNESS])
        assertTrue(
            input.vector[WorldSignalDimension.HEALTH_STABILITY]!!
                .provenanceFingerprints.contains(hardware.fingerprint())
        )
    }

    private fun ResourceBudgetQuota.asRequestedUsage(): ResourceBudgetUsage = ResourceBudgetUsage(
        elapsedMillis = elapsedMillis,
        workUnits = workUnits,
        memoryBytes = memoryBytes,
        ioBytes = ioBytes,
        networkBytes = networkBytes,
        candidates = candidates,
    )
}
