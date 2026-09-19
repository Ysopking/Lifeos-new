package app.lifeos.core.runtime.resource

import app.lifeos.core.field.world.WorldNodeKind
import app.lifeos.core.field.world.WorldSignalDimension
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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

    @Test
    fun executionCapacityFingerprintIgnoresClockAndBucketsInsignificantJitter() {
        val first = HardwareStateSnapshot(
            observedAt = Instant.parse("2026-09-11T12:00:00Z"),
            availableProcessors = 8,
            thermalState = HardwareThermalState.NOMINAL,
            processCpuLoadFraction = 0.201,
            availableMemoryBytes = 800,
            totalMemoryBytes = 1_000,
        )
        val second = first.copy(
            observedAt = Instant.parse("2026-09-11T12:00:05Z"),
            processCpuLoadFraction = 0.209,
        )

        assertNotEquals(first.fingerprint(), second.fingerprint())
        assertEquals(first.executionCapacityFingerprint(), second.executionCapacityFingerprint())
    }

    @Test
    fun unknownCpuLoadIsConservativeInsteadOfFullyIdle() {
        val unknownLoad = HardwareStateSnapshot(
            observedAt = Instant.parse("2026-09-11T12:00:00Z"),
            availableProcessors = 8,
            thermalState = HardwareThermalState.NOMINAL,
            availableMemoryBytes = 1_000,
            totalMemoryBytes = 1_000,
        )
        val explicitIdle = unknownLoad.copy(processCpuLoadFraction = 0.0)

        assertTrue(
            unknownLoad.executionComputeHeadroom() <
                explicitIdle.executionComputeHeadroom()
        )
    }

    @Test
    fun measuredProcessLoadContractsExecutionWithoutRevokingLegacyQuotaAdmission() {
        val idle = HardwareStateSnapshot(
            observedAt = Instant.parse("2026-09-11T12:00:00Z"),
            availableProcessors = 8,
            thermalState = HardwareThermalState.NOMINAL,
            processCpuLoadFraction = 0.0,
            availableMemoryBytes = 800,
            totalMemoryBytes = 1_000,
        )
        val busy = idle.copy(processCpuLoadFraction = 0.95)
        val requested = ResourceBudgetUsage(
            elapsedMillis = 1_500,
            workUnits = 2,
            memoryBytes = 8,
            ioBytes = 1,
            candidates = 1,
        )

        val idleAdmission = optimizer.plan(hardQuota, requested, idle)
        val busyAdmission = optimizer.plan(hardQuota, requested, busy)

        assertEquals(idleAdmission.effectiveQuota, busyAdmission.effectiveQuota)
        assertTrue(busyAdmission.requestedFits)
        assertTrue(busy.executionComputeHeadroom() < idle.executionComputeHeadroom())
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
