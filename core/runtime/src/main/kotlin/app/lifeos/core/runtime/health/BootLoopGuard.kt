package app.lifeos.core.runtime.health

interface BootAttemptStore {
    fun read(): Int
    /** Must be durably acknowledged before returning. */
    fun write(attempts: Int)
}

/** A successful vault read alone must not reset the guard; wait for stable runtime operation. */
class BootLoopGuard(private val store: BootAttemptStore, private val threshold: Int = 3) {
    init { require(threshold > 1) }
    @Synchronized fun begin(): Boolean {
        val count = store.read()
        check(count >= 0) { "Invalid boot attempt count" }
        val next = if (count >= threshold) threshold else count + 1
        store.write(next)
        return next >= threshold
    }
    @Synchronized fun markStable() = store.write(0)
}
