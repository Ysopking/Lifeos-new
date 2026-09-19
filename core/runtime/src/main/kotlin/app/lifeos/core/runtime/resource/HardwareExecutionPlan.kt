package app.lifeos.core.runtime.resource

import app.lifeos.core.field.StableFieldIds

enum class HardwareExecutionClass {
    CPU_COMPUTE,
    CPU_LIGHT,
    IO,
    MEMORY,
    ACCELERATOR,
}

data class ExecutionLaneBudget(
    val interactiveReservedSlots: Int,
    val activeSlots: Int,
    val backgroundSlots: Int,
    val maintenanceSlots: Int,
) {
    init {
        require(interactiveReservedSlots >= 0)
        require(activeSlots >= 0)
        require(backgroundSlots >= 0)
        require(maintenanceSlots >= 0)
    }

    val totalSlots: Int
        get() = interactiveReservedSlots + activeSlots + backgroundSlots + maintenanceSlots
}

data class AdaptiveWorkQuantum(
    val targetWorkUnits: Long,
    val maximumBatchSize: Int,
    val yieldAfterWorkUnits: Long,
) {
    init {
        require(targetWorkUnits > 0L)
        require(maximumBatchSize > 0)
        require(yieldAfterWorkUnits >= targetWorkUnits)
    }
}

data class HardwareExecutionStrategyProfile(
    val strategyId: String,
    val maxConcurrentTasks: Int,
    val maxComputeTasks: Int,
    val maxIoTasks: Int,
    val minWorkQuantum: Long,
    val maxWorkQuantum: Long,
    val minBatchSize: Int,
    val maxBatchSize: Int,
) {
    init {
        require(strategyId.isNotBlank())
        require(maxConcurrentTasks > 0)
        require(maxComputeTasks in 1..maxConcurrentTasks)
        require(maxIoTasks in 1..maxConcurrentTasks)
        require(minWorkQuantum > 0L && maxWorkQuantum >= minWorkQuantum)
        require(maxWorkQuantum <= Long.MAX_VALUE / 4L)
        require(minBatchSize > 0 && maxBatchSize >= minBatchSize)
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "hardware-execution-strategy-profile/v1",
        strategyId,
        maxConcurrentTasks.toString(),
        maxComputeTasks.toString(),
        maxIoTasks.toString(),
        minWorkQuantum.toString(),
        maxWorkQuantum.toString(),
        minBatchSize.toString(),
        maxBatchSize.toString(),
    )
}

data class HardwareExecutionLearningProfile(
    val parallelismScalar: Double = 0.5,
    val quantumScalar: Double = 0.5,
    val batchScalar: Double = 0.5,
    val ioParallelismScalar: Double = 0.5,
) {
    init {
        listOf(parallelismScalar, quantumScalar, batchScalar, ioParallelismScalar).forEach {
            require(it.isFinite() && it in 0.0..1.0)
        }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "hardware-execution-learning-profile/v1",
        java.lang.Double.toHexString(parallelismScalar),
        java.lang.Double.toHexString(quantumScalar),
        java.lang.Double.toHexString(batchScalar),
        java.lang.Double.toHexString(ioParallelismScalar),
    )
}

@JvmInline
value class HardwareExecutionPlanFingerprint(val value: String) {
    init { require(value.matches(Regex("[0-9a-f]{64}"))) }
    override fun toString(): String = value
}

data class HardwareExecutionPlan(
    val hardwareSnapshotFingerprint: String,
    val executionCapacityFingerprint: String,
    val worldSnapshotId: String,
    val executionStrategyFingerprint: String,
    val learningProfileFingerprint: String,
    val maxConcurrentTasks: Int,
    val maxComputeTasks: Int,
    val maxIoTasks: Int,
    val laneBudget: ExecutionLaneBudget,
    val workQuantum: AdaptiveWorkQuantum,
    val memoryPerTaskBytes: Long,
) {
    init {
        require(hardwareSnapshotFingerprint.isNotBlank())
        require(executionCapacityFingerprint.isNotBlank())
        require(worldSnapshotId.isNotBlank())
        require(executionStrategyFingerprint.isNotBlank())
        require(learningProfileFingerprint.isNotBlank())
        require(maxConcurrentTasks >= 0)
        require(maxComputeTasks in 0..maxConcurrentTasks)
        require(maxIoTasks in 0..maxConcurrentTasks)
        require(laneBudget.totalSlots == maxConcurrentTasks) {
            "Lane budget must exactly partition active execution slots"
        }
        require(memoryPerTaskBytes >= 0L)
    }

    val interactiveReservedSlots: Int get() = laneBudget.interactiveReservedSlots
    val backgroundSlots: Int get() = laneBudget.backgroundSlots
    val maintenanceSlots: Int get() = laneBudget.maintenanceSlots
    val targetWorkQuantum: Long get() = workQuantum.targetWorkUnits
    val maximumBatchSize: Int get() = workQuantum.maximumBatchSize
    val yieldAfterWorkUnits: Long get() = workQuantum.yieldAfterWorkUnits

    fun fingerprint(): HardwareExecutionPlanFingerprint = HardwareExecutionPlanFingerprint(
        StableFieldIds.fingerprint(
            "hardware-execution-plan/v1",
            executionCapacityFingerprint,
            worldSnapshotId,
            executionStrategyFingerprint,
            learningProfileFingerprint,
            maxConcurrentTasks.toString(),
            maxComputeTasks.toString(),
            maxIoTasks.toString(),
            interactiveReservedSlots.toString(),
            laneBudget.activeSlots.toString(),
            backgroundSlots.toString(),
            maintenanceSlots.toString(),
            targetWorkQuantum.toString(),
            maximumBatchSize.toString(),
            yieldAfterWorkUnits.toString(),
            memoryPerTaskBytes.toString(),
        )
    )
}
