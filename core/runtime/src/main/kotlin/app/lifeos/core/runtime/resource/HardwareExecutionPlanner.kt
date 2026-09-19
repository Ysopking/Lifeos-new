package app.lifeos.core.runtime.resource

import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Converts the already-authorized V16 hardware budget into an execution topology. It cannot expand
 * the supplied effective quota and it never changes owner/resource authority.
 */
class HardwareExecutionPlanner {
    fun plan(
        hardware: HardwareStateSnapshot,
        budgetPlan: HardwareAdaptiveBudgetPlan,
        worldSnapshotId: String,
        strategy: HardwareExecutionStrategyProfile,
        learning: HardwareExecutionLearningProfile,
        priority: HardwareWorkPriority = HardwareWorkPriority.NORMAL,
    ): HardwareExecutionPlan {
        require(worldSnapshotId.isNotBlank())
        require(budgetPlan.hardwareSnapshotFingerprint == hardware.fingerprint()) {
            "Hardware budget and execution planning observation differ"
        }

        val suspended = budgetPlan.mode == HardwareBudgetMode.SUSPENDED ||
            budgetPlan.effectiveQuota.workUnits == 0L
        val physicalLimit = minOf(hardware.availableProcessors, strategy.maxConcurrentTasks)
        val capacity = (hardware.computeHeadroom() * priorityBoost(priority)).coerceIn(0.0, 1.0)
        val capacityLimit = if (suspended) 0 else {
            ceil(physicalLimit.toDouble() * capacity)
                .toInt()
                .coerceIn(1, physicalLimit)
        }
        val learnedLimit = if (suspended) 0 else {
            interpolateInt(1, physicalLimit, learning.parallelismScalar)
        }
        val total = minOf(capacityLimit, learnedLimit, physicalLimit)

        val interactive = if (total > 0) 1 else 0
        val remainingAfterInteractive = (total - interactive).coerceAtLeast(0)
        val maintenance = if (remainingAfterInteractive >= 4) 1 else 0
        val background = if (remainingAfterInteractive - maintenance >= 2) 1 else 0
        val active = total - interactive - background - maintenance

        val computeLimit = if (total == 0) 0 else minOf(strategy.maxComputeTasks, total)
        val learnedIoLimit = if (total == 0) 0 else {
            interpolateInt(1, minOf(strategy.maxIoTasks, total), learning.ioParallelismScalar)
        }
        val ioLimit = minOf(strategy.maxIoTasks, total, learnedIoLimit)

        val quantum = interpolateLong(strategy.minWorkQuantum, strategy.maxWorkQuantum, learning.quantumScalar)
        val batch = interpolateInt(strategy.minBatchSize, strategy.maxBatchSize, learning.batchScalar)
        val memoryPerTask = if (total == 0) 0L else budgetPlan.effectiveQuota.memoryBytes / total.toLong()

        return HardwareExecutionPlan(
            hardwareSnapshotFingerprint = hardware.fingerprint(),
            executionCapacityFingerprint = hardware.executionCapacityFingerprint(),
            worldSnapshotId = worldSnapshotId,
            executionStrategyFingerprint = strategy.fingerprint(),
            learningProfileFingerprint = learning.fingerprint(),
            maxConcurrentTasks = total,
            maxComputeTasks = computeLimit,
            maxIoTasks = ioLimit,
            laneBudget = ExecutionLaneBudget(
                interactiveReservedSlots = interactive,
                activeSlots = active,
                backgroundSlots = background,
                maintenanceSlots = maintenance,
            ),
            workQuantum = AdaptiveWorkQuantum(
                targetWorkUnits = quantum,
                maximumBatchSize = batch,
                yieldAfterWorkUnits = quantum * 4L,
            ),
            memoryPerTaskBytes = memoryPerTask,
        )
    }

    private fun interpolateInt(min: Int, max: Int, scalar: Double): Int {
        if (min == max) return min
        return (min + (max - min).toDouble() * scalar).roundToInt().coerceIn(min, max)
    }

    private fun interpolateLong(min: Long, max: Long, scalar: Double): Long {
        if (min == max) return min
        return (min.toDouble() + (max - min).toDouble() * scalar)
            .roundToLong()
            .coerceIn(min, max)
    }

    private fun priorityBoost(priority: HardwareWorkPriority): Double = when (priority) {
        HardwareWorkPriority.LOW -> 0.80
        HardwareWorkPriority.NORMAL -> 1.00
        HardwareWorkPriority.HIGH -> 1.12
        HardwareWorkPriority.CRITICAL -> 1.25
    }
}
