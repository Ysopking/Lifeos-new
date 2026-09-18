package app.lifeos.core.image.nativebackend

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

enum class MmsiHardwareFailureKind {
    DEVICE_LOST,
    CONTEXT_LOST,
    OUT_OF_MEMORY,
    DRIVER_FAILURE,
    DISPATCH_REJECTED,
    UNKNOWN,
}

enum class MmsiHardwarePathState {
    HEALTHY,
    DEGRADED,
    QUARANTINED,
}

data class MmsiHardwareHealthSnapshot(
    val state: MmsiHardwarePathState,
    val consecutiveFailures: Int,
    val lastFailure: MmsiHardwareFailureKind?,
)

/**
 * Process-local safety guard for optional GPU acceleration. It carries no rendering truth: when the
 * path is quarantined callers must use the deterministic CPU implementation.
 */
class MmsiHardwareHealthGuard(
    private val quarantineAfterFailures: Int = 2,
) {
    private val failures = AtomicInteger(0)
    private val lastFailure = AtomicReference<MmsiHardwareFailureKind?>(null)

    init { require(quarantineAfterFailures > 0) }

    fun canAttemptHardware(): Boolean = failures.get() < quarantineAfterFailures

    fun snapshot(): MmsiHardwareHealthSnapshot {
        val count = failures.get()
        val state = when {
            count <= 0 -> MmsiHardwarePathState.HEALTHY
            count < quarantineAfterFailures -> MmsiHardwarePathState.DEGRADED
            else -> MmsiHardwarePathState.QUARANTINED
        }
        return MmsiHardwareHealthSnapshot(state, count, lastFailure.get())
    }

    fun recordSuccess() {
        failures.set(0)
        lastFailure.set(null)
    }

    fun recordRejected() {
        recordFailure(MmsiHardwareFailureKind.DISPATCH_REJECTED)
    }

    fun recordFailure(error: Throwable) {
        recordFailure(classify(error))
    }

    fun resetAfterLifecycleRecovery() {
        failures.set(0)
        lastFailure.set(null)
    }

    private fun recordFailure(kind: MmsiHardwareFailureKind) {
        lastFailure.set(kind)
        failures.updateAndGet { previous ->
            (previous + 1).coerceAtMost(quarantineAfterFailures)
        }
    }

    private fun classify(error: Throwable): MmsiHardwareFailureKind {
        if (error is OutOfMemoryError) return MmsiHardwareFailureKind.OUT_OF_MEMORY
        val message = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase()
        return when {
            "device lost" in message || "vk_error_device_lost" in message ->
                MmsiHardwareFailureKind.DEVICE_LOST
            "context lost" in message || "surface lost" in message ->
                MmsiHardwareFailureKind.CONTEXT_LOST
            "out of memory" in message || "vk_error_out_of" in message ->
                MmsiHardwareFailureKind.OUT_OF_MEMORY
            "driver" in message || "vulkan" in message ->
                MmsiHardwareFailureKind.DRIVER_FAILURE
            else -> MmsiHardwareFailureKind.UNKNOWN
        }
    }
}
