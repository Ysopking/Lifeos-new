package app.lifeos.core.runtime.resource

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.world.WorldDimensionValue
import app.lifeos.core.field.world.WorldFieldVector
import app.lifeos.core.field.world.WorldNodeKind
import app.lifeos.core.field.world.WorldSignalDimension
import app.lifeos.core.field.world.WorldTargetRef
import app.lifeos.core.runtime.world.WorldFormulaInputSnapshot
import java.time.Instant

/** Platform-neutral thermal state so core/runtime does not depend on Android APIs. */
enum class HardwareThermalState {
    UNKNOWN,
    NOMINAL,
    FAIR,
    SERIOUS,
    CRITICAL,
    EMERGENCY,
    SHUTDOWN,
}

/**
 * A measured view of the device that is safe to persist or project into the World Formula.
 * Unknown measurements stay nullable instead of being silently invented.
 */
data class HardwareStateSnapshot(
    val observedAt: Instant,
    val availableProcessors: Int,
    val batteryFraction: Double? = null,
    val charging: Boolean? = null,
    val thermalState: HardwareThermalState = HardwareThermalState.UNKNOWN,
    /** Legacy whole-process load input retained for source compatibility. */
    val cpuLoadFraction: Double? = null,
    /** Preferred measured process CPU load normalized by visible logical processors. */
    val processCpuLoadFraction: Double? = null,
    /** Explicit pressure signals are normalized as 0 = idle/free and 1 = saturated. */
    val memoryPressureFraction: Double? = null,
    val ioPressureFraction: Double? = null,
    /** Optional stable platform accelerator identity; null means no trusted accelerator signal. */
    val acceleratorFingerprint: String? = null,
    val availableMemoryBytes: Long? = null,
    val totalMemoryBytes: Long? = null,
    val availableStorageBytes: Long? = null,
    val totalStorageBytes: Long? = null,
) {
    init {
        require(availableProcessors > 0) { "Available processor count must be positive" }
        require(batteryFraction == null || batteryFraction.isFinite() && batteryFraction in 0.0..1.0) {
            "Battery fraction must be in 0..1"
        }
        require(cpuLoadFraction == null || cpuLoadFraction.isFinite() && cpuLoadFraction in 0.0..1.0) {
            "CPU load fraction must be in 0..1"
        }
        require(
            processCpuLoadFraction == null ||
                processCpuLoadFraction.isFinite() && processCpuLoadFraction in 0.0..1.0
        ) { "Process CPU load fraction must be in 0..1" }
        require(
            memoryPressureFraction == null ||
                memoryPressureFraction.isFinite() && memoryPressureFraction in 0.0..1.0
        ) { "Memory pressure fraction must be in 0..1" }
        require(
            ioPressureFraction == null ||
                ioPressureFraction.isFinite() && ioPressureFraction in 0.0..1.0
        ) { "IO pressure fraction must be in 0..1" }
        require(acceleratorFingerprint == null || acceleratorFingerprint.isNotBlank()) {
            "Accelerator fingerprint must not be blank"
        }
        require((availableMemoryBytes == null) == (totalMemoryBytes == null)) {
            "Memory availability and total must either both be known or both be unknown"
        }
        require((availableStorageBytes == null) == (totalStorageBytes == null)) {
            "Storage availability and total must either both be known or both be unknown"
        }
        if (availableMemoryBytes != null && totalMemoryBytes != null) {
            require(totalMemoryBytes > 0L && availableMemoryBytes in 0L..totalMemoryBytes) {
                "Memory measurements are invalid"
            }
        }
        if (availableStorageBytes != null && totalStorageBytes != null) {
            require(totalStorageBytes > 0L && availableStorageBytes in 0L..totalStorageBytes) {
                "Storage measurements are invalid"
            }
        }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "hardware-state-snapshot/v2",
        observedAt.toString(),
        availableProcessors.toString(),
        batteryFraction?.let(java.lang.Double::toHexString).orEmpty(),
        charging?.toString().orEmpty(),
        thermalState.name,
        cpuLoadFraction?.let(java.lang.Double::toHexString).orEmpty(),
        processCpuLoadFraction?.let(java.lang.Double::toHexString).orEmpty(),
        memoryPressureFraction?.let(java.lang.Double::toHexString).orEmpty(),
        ioPressureFraction?.let(java.lang.Double::toHexString).orEmpty(),
        acceleratorFingerprint.orEmpty(),
        availableMemoryBytes?.toString().orEmpty(),
        totalMemoryBytes?.toString().orEmpty(),
        availableStorageBytes?.toString().orEmpty(),
        totalStorageBytes?.toString().orEmpty(),
    )

    fun thermalHeadroom(): Double = when (thermalState) {
        HardwareThermalState.UNKNOWN -> 0.70
        HardwareThermalState.NOMINAL -> 1.00
        HardwareThermalState.FAIR -> 0.85
        HardwareThermalState.SERIOUS -> 0.55
        HardwareThermalState.CRITICAL -> 0.25
        HardwareThermalState.EMERGENCY -> 0.08
        HardwareThermalState.SHUTDOWN -> 0.00
    }

    fun memoryHeadroom(): Double? =
        memoryPressureFraction?.let { (1.0 - it).coerceIn(0.0, 1.0) }
            ?: ratio(availableMemoryBytes, totalMemoryBytes)

    fun ioHeadroom(): Double? =
        ioPressureFraction?.let { (1.0 - it).coerceIn(0.0, 1.0) }

    fun storageHeadroom(): Double? = ratio(availableStorageBytes, totalStorageBytes)

    fun effectiveProcessCpuLoadFraction(): Double? = processCpuLoadFraction ?: cpuLoadFraction

    /**
     * Stable scheduling identity. Unlike [fingerprint], this deliberately excludes [observedAt] and
     * quantizes noisy measurements so insignificant sampling jitter does not churn execution plans.
     */
    fun executionCapacityFingerprint(): String = StableFieldIds.fingerprint(
        "hardware-execution-capacity/v1",
        availableProcessors.toString(),
        thermalState.name,
        bucket(effectiveProcessCpuLoadFraction()),
        bucket(memoryHeadroom()),
        bucket(ioHeadroom()),
        bucket(storageHeadroom()),
        bucket(energyAvailability()),
        charging?.toString().orEmpty(),
        acceleratorFingerprint.orEmpty(),
    )

    fun energyAvailability(): Double? = when {
        charging == true -> maxOf(batteryFraction ?: 0.90, 0.90)
        batteryFraction != null -> batteryFraction
        else -> null
    }

    /**
     * Processor capability is multiplied by measured process headroom, thermal headroom and the
     * current memory boundary. Missing process load is intentionally conservative instead of being
     * interpreted as a completely idle CPU.
     */
    fun computeHeadroom(): Double {
        val processorCapacity = (availableProcessors.toDouble() / REFERENCE_PROCESSORS)
            .coerceIn(MIN_PROCESSOR_CAPACITY, 1.0)
        val measuredLoad = effectiveProcessCpuLoadFraction() ?: UNKNOWN_PROCESS_CPU_LOAD
        val loadHeadroom = (1.0 - measuredLoad).coerceIn(0.0, 1.0)
        val memoryBoundary = memoryHeadroom()
            ?.let { headroom ->
                // RAM already has an independent hard quota. Compute is contracted only once
                // pressure becomes material, avoiding a second penalty on healthy mid-range RAM.
                if (headroom >= 0.50) 1.0 else (0.50 + headroom).coerceIn(0.50, 1.0)
            }
            // Unknown memory is already conservatively constrained by the independent memory quota.
            // Avoid applying the same uncertainty twice to compute capacity.
            ?: 1.0
        return (
            processorCapacity *
                loadHeadroom *
                thermalHeadroom() *
                memoryBoundary
            ).coerceIn(0.0, 1.0)
    }

    fun healthStability(): Double {
        val signals = buildList {
            add(thermalHeadroom())
            energyAvailability()?.let(::add)
            memoryHeadroom()?.let(::add)
            storageHeadroom()?.let(::add)
            ioHeadroom()?.let(::add)
        }
        return signals.minOrNull() ?: thermalHeadroom()
    }

    fun capabilityReadiness(): Double = minOf(
        computeHeadroom(),
        memoryHeadroom() ?: 1.0,
        energyAvailability() ?: 1.0,
    ).coerceIn(0.0, 1.0)

    fun overallCapacity(): Double = minOf(healthStability(), capabilityReadiness())

    fun shouldSuspendHeavyWork(): Boolean =
        thermalState == HardwareThermalState.EMERGENCY ||
            thermalState == HardwareThermalState.SHUTDOWN ||
            (charging != true && batteryFraction != null && batteryFraction <= CRITICAL_BATTERY_FRACTION)

    /**
     * Hardware becomes a first-class World Formula input without changing the existing World Formula
     * type system: device stability maps to HEALTH_STABILITY and executable capacity maps to
     * CAPABILITY_READINESS. The complete measured state remains bound by the source fingerprint.
     */
    fun toWorldFormulaInput(
        targetKey: String = "hardware:local-device",
    ): WorldFormulaInputSnapshot {
        require(targetKey.isNotBlank())
        val provenance = fingerprint()
        val values = listOf(
            WorldDimensionValue(
                dimension = WorldSignalDimension.HEALTH_STABILITY,
                value = healthStability(),
                confidence = if (thermalState == HardwareThermalState.UNKNOWN) 0.70 else 1.0,
                provenanceFingerprints = setOf(provenance),
            ),
            WorldDimensionValue(
                dimension = WorldSignalDimension.CAPABILITY_READINESS,
                value = capabilityReadiness(),
                confidence = if (effectiveProcessCpuLoadFraction() == null) 0.75 else 1.0,
                provenanceFingerprints = setOf(provenance),
            ),
            WorldDimensionValue(
                dimension = WorldSignalDimension.TEMPORAL_FRESHNESS,
                value = 1.0,
                confidence = 1.0,
                provenanceFingerprints = setOf(provenance),
            ),
        )
        return WorldFormulaInputSnapshot(
            target = WorldTargetRef(
                kind = WorldNodeKind.HEALTH,
                key = targetKey,
            ),
            vector = WorldFieldVector(values),
            sourceSnapshotFingerprint = provenance,
        )
    }

    private fun ratio(available: Long?, total: Long?): Double? =
        if (available == null || total == null) null else available.toDouble() / total.toDouble()

    private fun bucket(value: Double?): String =
        value?.let { ((it.coerceIn(0.0, 1.0) * CAPACITY_BUCKETS).toInt()).toString() }.orEmpty()

    private companion object {
        const val REFERENCE_PROCESSORS = 8.0
        const val MIN_PROCESSOR_CAPACITY = 0.125
        // Slightly non-zero so unknown process load is never treated as a perfectly idle CPU.
        const val UNKNOWN_PROCESS_CPU_LOAD = 0.04
        const val CAPACITY_BUCKETS = 20.0
        const val CRITICAL_BATTERY_FRACTION = 0.02
    }
}

enum class HardwareWorkPriority {
    LOW,
    NORMAL,
    HIGH,
    CRITICAL,
}

enum class HardwareBudgetMode {
    NORMAL,
    CONSERVATIVE,
    THROTTLED,
    SUSPENDED,
}

data class HardwareAdaptiveBudgetPlan(
    val hardQuota: ResourceBudgetQuota,
    val effectiveQuota: ResourceBudgetQuota,
    val requested: ResourceBudgetUsage,
    val recommendedReservation: ResourceBudgetUsage,
    val mode: HardwareBudgetMode,
    val reasons: List<String>,
    val hardwareSnapshotFingerprint: String,
    val hardwareWorldInput: WorldFormulaInputSnapshot,
) {
    init {
        require(effectiveQuota.isWithin(hardQuota)) {
            "Hardware-adaptive quota may never exceed its hard owner/system quota"
        }
        require(recommendedReservation.isWithin(effectiveQuota.asUsageLimit())) {
            "Recommended reservation exceeds hardware-adaptive quota"
        }
        require(hardwareSnapshotFingerprint.isNotBlank())
        require(reasons.none { it.isBlank() })
    }

    val requestedFits: Boolean = requested.isWithin(effectiveQuota.asUsageLimit())
}

/**
 * V16 hardware-adaptive resource intelligence. Hard quotas remain absolute safety limits. This
 * optimizer can only shrink the currently usable envelope; priority may reclaim capacity only up to
 * those hard limits and can never override thermal emergency/shutdown suspension.
 */
class HardwareAdaptiveResourceOptimizer {
    fun plan(
        hardQuota: ResourceBudgetQuota,
        requested: ResourceBudgetUsage,
        hardware: HardwareStateSnapshot,
        priority: HardwareWorkPriority = HardwareWorkPriority.NORMAL,
    ): HardwareAdaptiveBudgetPlan {
        if (hardware.shouldSuspendHeavyWork()) {
            val zero = ResourceBudgetQuota(0, 0, 0, 0, 0, 0)
            return HardwareAdaptiveBudgetPlan(
                hardQuota = hardQuota,
                effectiveQuota = zero,
                requested = requested,
                recommendedReservation = ResourceBudgetUsage(),
                mode = HardwareBudgetMode.SUSPENDED,
                reasons = suspensionReasons(hardware),
                hardwareSnapshotFingerprint = hardware.fingerprint(),
                hardwareWorldInput = hardware.toWorldFormulaInput(),
            )
        }

        val boost = priorityBoost(priority)
        val thermal = hardware.thermalHeadroom()
        val compute = minOf(thermal, (hardware.computeHeadroom() * boost).coerceAtMost(1.0))
        val memory = hardware.memoryHeadroom() ?: UNKNOWN_MEMORY_HEADROOM
        val storage = hardware.storageHeadroom() ?: UNKNOWN_STORAGE_HEADROOM
        val energy = ((hardware.energyAvailability() ?: UNKNOWN_ENERGY_AVAILABILITY) * boost)
            .coerceAtMost(1.0)

        val effective = ResourceBudgetQuota(
            // Wall-clock remains a hard timeout. Throttling work rather than shortening the timeout
            // avoids turning a slower thermal state into accidental timeout failures.
            elapsedMillis = hardQuota.elapsedMillis,
            workUnits = scale(hardQuota.workUnits, compute),
            memoryBytes = scale(hardQuota.memoryBytes, memory),
            ioBytes = scale(hardQuota.ioBytes, minOf(storage, thermal)),
            networkBytes = scale(hardQuota.networkBytes, energy),
            candidates = scale(hardQuota.candidates, minOf(compute, memory)),
        )
        val recommended = requested.cappedBy(effective)
        val fits = requested.isWithin(effective.asUsageLimit())
        val mode = when {
            !fits -> HardwareBudgetMode.THROTTLED
            hardware.overallCapacity() < CONSERVATIVE_THRESHOLD -> HardwareBudgetMode.CONSERVATIVE
            else -> HardwareBudgetMode.NORMAL
        }
        return HardwareAdaptiveBudgetPlan(
            hardQuota = hardQuota,
            effectiveQuota = effective,
            requested = requested,
            recommendedReservation = recommended,
            mode = mode,
            reasons = buildReasons(hardware, fits),
            hardwareSnapshotFingerprint = hardware.fingerprint(),
            hardwareWorldInput = hardware.toWorldFormulaInput(),
        )
    }

    private fun buildReasons(
        hardware: HardwareStateSnapshot,
        requestedFits: Boolean,
    ): List<String> = buildList {
        if (!requestedFits) add("requested-work-exceeds-current-hardware-envelope")
        if (hardware.thermalHeadroom() < 0.80) add("thermal-headroom-reduced")
        if ((hardware.energyAvailability() ?: 1.0) < 0.25) add("energy-availability-low")
        if ((hardware.memoryHeadroom() ?: 1.0) < 0.25) add("memory-headroom-low")
        if ((hardware.storageHeadroom() ?: 1.0) < 0.10) add("storage-headroom-low")
    }

    private fun suspensionReasons(hardware: HardwareStateSnapshot): List<String> = buildList {
        if (
            hardware.thermalState == HardwareThermalState.EMERGENCY ||
            hardware.thermalState == HardwareThermalState.SHUTDOWN
        ) {
            add("thermal-state-requires-suspension")
        }
        if (hardware.charging != true && hardware.batteryFraction != null && hardware.batteryFraction <= 0.02) {
            add("battery-state-requires-suspension")
        }
        if (isEmpty()) add("hardware-state-requires-suspension")
    }

    private fun priorityBoost(priority: HardwareWorkPriority): Double = when (priority) {
        HardwareWorkPriority.LOW -> 0.80
        HardwareWorkPriority.NORMAL -> 1.00
        HardwareWorkPriority.HIGH -> 1.12
        HardwareWorkPriority.CRITICAL -> 1.25
    }

    private fun scale(value: Long, factor: Double): Long {
        require(factor.isFinite() && factor in 0.0..1.0)
        if (value == 0L || factor == 0.0) return 0L
        return (value.toDouble() * factor)
            .toLong()
            .coerceAtLeast(1L)
            .coerceAtMost(value)
    }

    private companion object {
        const val UNKNOWN_MEMORY_HEADROOM = 0.70
        const val UNKNOWN_STORAGE_HEADROOM = 0.80
        const val UNKNOWN_ENERGY_AVAILABILITY = 0.70
        const val CONSERVATIVE_THRESHOLD = 0.75
    }
}

private fun ResourceBudgetQuota.isWithin(hard: ResourceBudgetQuota): Boolean =
    elapsedMillis <= hard.elapsedMillis &&
        workUnits <= hard.workUnits &&
        memoryBytes <= hard.memoryBytes &&
        ioBytes <= hard.ioBytes &&
        networkBytes <= hard.networkBytes &&
        candidates <= hard.candidates

private fun ResourceBudgetQuota.asUsageLimit(): ResourceBudgetUsage = ResourceBudgetUsage(
    elapsedMillis = elapsedMillis,
    workUnits = workUnits,
    memoryBytes = memoryBytes,
    ioBytes = ioBytes,
    networkBytes = networkBytes,
    candidates = candidates,
)

private fun ResourceBudgetUsage.cappedBy(quota: ResourceBudgetQuota): ResourceBudgetUsage =
    ResourceBudgetUsage(
        elapsedMillis = minOf(elapsedMillis, quota.elapsedMillis),
        workUnits = minOf(workUnits, quota.workUnits),
        memoryBytes = minOf(memoryBytes, quota.memoryBytes),
        ioBytes = minOf(ioBytes, quota.ioBytes),
        networkBytes = minOf(networkBytes, quota.networkBytes),
        candidates = minOf(candidates, quota.candidates),
    )
