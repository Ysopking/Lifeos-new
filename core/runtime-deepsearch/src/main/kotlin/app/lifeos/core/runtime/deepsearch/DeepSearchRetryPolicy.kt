package app.lifeos.core.runtime.deepsearch

import java.io.IOException

enum class DeepSearchSourceFailureClass {
    RETRYABLE,
    PERMANENT,
}

/** Marker for source adapters that can explicitly signal a transient, retry-safe failure. */
class DeepSearchRetryableSourceException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

data class DeepSearchRetryPolicy(
    val maxAttemptsPerExpansion: Int = 3,
    val baseBackoffMillis: Long = 100L,
    val maxBackoffMillis: Long = 1_000L,
) {
    init {
        require(maxAttemptsPerExpansion in 1..8)
        require(baseBackoffMillis in 0L..60_000L)
        require(maxBackoffMillis in baseBackoffMillis..120_000L)
    }

    fun classify(error: Throwable): DeepSearchSourceFailureClass = when (error) {
        is DeepSearchRetryableSourceException,
        is IOException,
        -> DeepSearchSourceFailureClass.RETRYABLE
        else -> DeepSearchSourceFailureClass.PERMANENT
    }

    /** failureNumber is 1-based: the first failed call schedules retry #1. */
    fun backoffMillis(failureNumber: Int): Long {
        require(failureNumber > 0)
        var value = baseBackoffMillis
        repeat((failureNumber - 1).coerceAtMost(30)) {
            value = if (value > maxBackoffMillis / 2L) maxBackoffMillis else value * 2L
        }
        return value.coerceAtMost(maxBackoffMillis)
    }
}
