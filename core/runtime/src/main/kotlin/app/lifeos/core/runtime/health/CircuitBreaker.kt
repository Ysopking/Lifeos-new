package app.lifeos.core.runtime.health

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class CircuitState {
    CLOSED,
    OPEN,
    HALF_OPEN,
}

data class CircuitBreakerPolicy(
    val failureThreshold: Int = 3,
    val openDuration: Duration = Duration.ofSeconds(30),
) {
    init {
        require(failureThreshold > 0) { "Circuit failure threshold must be positive" }
        require(!openDuration.isZero && !openDuration.isNegative) {
            "Circuit open duration must be positive"
        }
    }
}

data class CircuitPermit internal constructor(
    val nodeId: HealthNodeId,
    internal val generation: Long,
    val probe: Boolean,
)

sealed interface CircuitAcquireResult {
    data class Granted(val permit: CircuitPermit) : CircuitAcquireResult

    data class Rejected(
        val nodeId: HealthNodeId,
        val state: CircuitState,
        val retryAt: Instant?,
    ) : CircuitAcquireResult
}

data class CircuitBreakerSnapshot(
    val nodeId: HealthNodeId,
    val state: CircuitState,
    val consecutiveFailures: Int,
    val openedUntil: Instant?,
    val probeInFlight: Boolean,
    val generation: Long,
)

/**
 * Generation-safe circuit breaker. Stale permits cannot close or mutate a circuit
 * after another caller opened/reopened it.
 */
class CircuitBreaker(
    private val policy: CircuitBreakerPolicy = CircuitBreakerPolicy(),
) {
    private data class Record(
        val state: CircuitState = CircuitState.CLOSED,
        val consecutiveFailures: Int = 0,
        val openedUntil: Instant? = null,
        val probeInFlight: Boolean = false,
        val generation: Long = 0,
    )

    private val mutex = Mutex()
    private val records = mutableMapOf<HealthNodeId, Record>()

    suspend fun acquire(nodeId: HealthNodeId, at: Instant): CircuitAcquireResult = mutex.withLock {
        val current = records[nodeId] ?: Record()
        when (current.state) {
            CircuitState.CLOSED -> {
                records[nodeId] = current
                CircuitAcquireResult.Granted(
                    CircuitPermit(nodeId, current.generation, probe = false)
                )
            }

            CircuitState.OPEN -> {
                val openedUntil = checkNotNull(current.openedUntil)
                if (at.isBefore(openedUntil)) {
                    CircuitAcquireResult.Rejected(nodeId, CircuitState.OPEN, openedUntil)
                } else {
                    val halfOpen = current.copy(
                        state = CircuitState.HALF_OPEN,
                        probeInFlight = true,
                        generation = current.generation + 1,
                    )
                    records[nodeId] = halfOpen
                    CircuitAcquireResult.Granted(
                        CircuitPermit(nodeId, halfOpen.generation, probe = true)
                    )
                }
            }

            CircuitState.HALF_OPEN -> CircuitAcquireResult.Rejected(
                nodeId = nodeId,
                state = CircuitState.HALF_OPEN,
                retryAt = null,
            )
        }
    }

    suspend fun onSuccess(permit: CircuitPermit): Boolean = mutex.withLock {
        val current = records[permit.nodeId] ?: return@withLock false
        if (current.generation != permit.generation) return@withLock false

        records[permit.nodeId] = if (current.state == CircuitState.HALF_OPEN || permit.probe) {
            Record(generation = current.generation + 1)
        } else {
            current.copy(consecutiveFailures = 0)
        }
        true
    }

    suspend fun onFailure(permit: CircuitPermit, at: Instant): Boolean = mutex.withLock {
        val current = records[permit.nodeId] ?: return@withLock false
        if (current.generation != permit.generation) return@withLock false

        if (current.state == CircuitState.HALF_OPEN || permit.probe) {
            records[permit.nodeId] = current.copy(
                state = CircuitState.OPEN,
                consecutiveFailures = policy.failureThreshold,
                openedUntil = at.plus(policy.openDuration),
                probeInFlight = false,
                generation = current.generation + 1,
            )
            return@withLock true
        }

        val failures = current.consecutiveFailures + 1
        records[permit.nodeId] = if (failures >= policy.failureThreshold) {
            current.copy(
                state = CircuitState.OPEN,
                consecutiveFailures = failures,
                openedUntil = at.plus(policy.openDuration),
                probeInFlight = false,
                generation = current.generation + 1,
            )
        } else {
            current.copy(consecutiveFailures = failures)
        }
        true
    }

    suspend fun snapshot(nodeId: HealthNodeId): CircuitBreakerSnapshot = mutex.withLock {
        val current = records[nodeId] ?: Record()
        current.toSnapshot(nodeId)
    }

    private fun Record.toSnapshot(nodeId: HealthNodeId) = CircuitBreakerSnapshot(
        nodeId = nodeId,
        state = state,
        consecutiveFailures = consecutiveFailures,
        openedUntil = openedUntil,
        probeInFlight = probeInFlight,
        generation = generation,
    )
}
