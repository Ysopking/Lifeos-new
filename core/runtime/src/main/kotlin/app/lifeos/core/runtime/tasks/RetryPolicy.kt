package app.lifeos.core.runtime.tasks

import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.runtime.RuntimeFailure
import app.lifeos.core.runtime.RuntimeFailureCategory
import java.time.Duration
import java.time.Instant

data class RetrySchedule(
    val retryAt: Instant,
    val delay: Duration,
)

class RetryPolicy(
    private val initialDelay: Duration = Duration.ofSeconds(2),
    private val maxDelay: Duration = Duration.ofSeconds(30),
    private val jitterPermille: Int = 200,
) {
    init {
        require(!initialDelay.isZero && !initialDelay.isNegative) {
            "Retry initial delay must be positive"
        }
        require(maxDelay >= initialDelay) {
            "Retry max delay must not be shorter than initial delay"
        }
        require(jitterPermille in 0..500) {
            "Retry jitter must be between 0 and 500 permille"
        }
    }

    fun nextRetry(
        task: LifeTask,
        failures: List<RuntimeFailure>,
        scheduledAt: Instant,
    ): RetrySchedule? {
        if (failures.isEmpty()) return null
        if (task.attempt >= task.maxAttempts) return null
        if (failures.any { !isRetryable(it) }) return null

        val baseMillis = exponentialDelayMillis(task.attempt)
        val adjustedMillis = applyDeterministicJitter(
            baseMillis = baseMillis,
            seed = "${task.id.value}:${task.attempt}",
        )
        val delay = Duration.ofMillis(adjustedMillis)
        return RetrySchedule(
            retryAt = scheduledAt.plus(delay),
            delay = delay,
        )
    }

    private fun isRetryable(failure: RuntimeFailure): Boolean =
        failure.recoverable && failure.category != RuntimeFailureCategory.INVARIANT

    private fun exponentialDelayMillis(attempt: Int): Long {
        val maxMillis = maxDelay.toMillis()
        var delayMillis = initialDelay.toMillis()
        repeat((attempt - 1).coerceAtLeast(0)) {
            if (delayMillis >= maxMillis) return maxMillis
            delayMillis = minOf(maxMillis, delayMillis * 2)
        }
        return delayMillis
    }

    private fun applyDeterministicJitter(baseMillis: Long, seed: String): Long {
        if (jitterPermille == 0) return baseMillis.coerceAtMost(maxDelay.toMillis())

        val unsignedHash = seed.hashCode().toLong() and 0xffffffffL
        val signedBucket = (unsignedHash % 2001L) - 1000L
        val delta = baseMillis * jitterPermille.toLong() * signedBucket / 1_000_000L
        return (baseMillis + delta)
            .coerceAtLeast(1L)
            .coerceAtMost(maxDelay.toMillis())
    }
}
