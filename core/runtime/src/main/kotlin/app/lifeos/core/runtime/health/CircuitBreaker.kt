package app.lifeos.core.runtime.health

/** Monotonic clock; generation tokens reject stale results. One half-open probe at a time. */
class CircuitBreaker(
    private val threshold: Int = 3,
    private val cooldownNanos: Long = 30_000_000_000L,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    init { require(threshold > 0); require(cooldownNanos > 0) }
    enum class State { CLOSED, OPEN, HALF_OPEN }
    @Volatile var state: State = State.CLOSED
        private set
    private var failures = 0
    private var openedAt = 0L
    private var generation = 0L

    @Synchronized fun acquire(): Long? {
        if (state == State.OPEN) {
            if (nanoTime() - openedAt < cooldownNanos) return null
            state = State.HALF_OPEN
            return generation
        }
        return if (state == State.CLOSED) generation else null
    }

    @Synchronized fun success(token: Long): Boolean {
        if (token != generation || state == State.OPEN) return false
        failures = 0
        state = State.CLOSED
        generation++
        return true
    }

    @Synchronized fun failure(token: Long): Boolean {
        if (token != generation || state == State.OPEN) return false
        failures++
        if (state == State.HALF_OPEN || failures >= threshold) {
            state = State.OPEN
            openedAt = nanoTime()
            generation++
        }
        return true
    }

    @Synchronized fun cancel(token: Long) {
        if (token == generation && state == State.HALF_OPEN) {
            state = State.OPEN
            openedAt = nanoTime()
            generation++
        }
    }
}
