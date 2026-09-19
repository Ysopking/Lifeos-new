package app.lifeos.core.runtime.resource

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HardwareExecutionPlanTest {
    private val hard = ResourceBudgetQuota(
        elapsedMillis = 10_000,
        workUnits = 10_000,
        memoryBytes = 800_000_000,
        ioBytes = 100_000_000,
        networkBytes = 0,
        candidates = 100,
    )
    private val optimizer = HardwareAdaptiveResourceOptimizer()
    private val planner = HardwareExecutionPlanner()
    private val strategy = HardwareExecutionStrategyProfile(
        strategyId = "baseline",
        maxConcurrentTasks = 8,
        maxComputeTasks = 6,
        maxIoTasks = 4,
        minWorkQuantum = 8,
        maxWorkQuantum = 128,
        minBatchSize = 1,
        maxBatchSize = 32,
    )

    @Test
    fun thermalPressureContractsConcurrencyWithoutChangingHardAuthority() {
        val cool = hardware(HardwareThermalState.NOMINAL, 0.05)
        val hot = hardware(HardwareThermalState.CRITICAL, 0.05)
        val requested = ResourceBudgetUsage(workUnits = 1_000, memoryBytes = 128_000_000)

        val coolPlan = planner.plan(
            cool,
            optimizer.plan(hard, requested, cool, HardwareWorkPriority.CRITICAL),
            "world:1",
            strategy,
            HardwareExecutionLearningProfile(parallelismScalar = 1.0),
            HardwareWorkPriority.CRITICAL,
        )
        val hotPlan = planner.plan(
            hot,
            optimizer.plan(hard, requested, hot, HardwareWorkPriority.CRITICAL),
            "world:1",
            strategy,
            HardwareExecutionLearningProfile(parallelismScalar = 1.0),
            HardwareWorkPriority.CRITICAL,
        )

        assertTrue(coolPlan.maxConcurrentTasks <= cool.availableProcessors)
        assertTrue(hotPlan.maxConcurrentTasks < coolPlan.maxConcurrentTasks)
        assertEquals(coolPlan.maxConcurrentTasks, coolPlan.laneBudget.totalSlots)
        assertEquals(hotPlan.maxConcurrentTasks, hotPlan.laneBudget.totalSlots)
    }

    @Test
    fun planFingerprintIsStableAcrossTimestampOnlyObservationChanges() {
        val first = hardware(HardwareThermalState.NOMINAL, 0.20)
        val second = first.copy(observedAt = first.observedAt.plusSeconds(5))
        val requested = ResourceBudgetUsage(workUnits = 1_000, memoryBytes = 128_000_000)
        val learning = HardwareExecutionLearningProfile()

        val firstPlan = planner.plan(
            first,
            optimizer.plan(hard, requested, first),
            "world:stable",
            strategy,
            learning,
        )
        val secondPlan = planner.plan(
            second,
            optimizer.plan(hard, requested, second),
            "world:stable",
            strategy,
            learning,
        )

        assertNotEquals(firstPlan.hardwareSnapshotFingerprint, secondPlan.hardwareSnapshotFingerprint)
        assertEquals(firstPlan.executionCapacityFingerprint, secondPlan.executionCapacityFingerprint)
        assertEquals(firstPlan.fingerprint(), secondPlan.fingerprint())
    }

    private fun hardware(
        thermal: HardwareThermalState,
        load: Double,
    ) = HardwareStateSnapshot(
        observedAt = Instant.parse("2026-09-19T00:00:00Z"),
        availableProcessors = 8,
        batteryFraction = 0.8,
        charging = true,
        thermalState = thermal,
        processCpuLoadFraction = load,
        availableMemoryBytes = 900,
        totalMemoryBytes = 1_000,
        availableStorageBytes = 900,
        totalStorageBytes = 1_000,
    )
}
