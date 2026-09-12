package app.lifeos.core.runtime.workers

/**
 * Process seam that lets the new causal Photon runtime observe productive durable task completion
 * without replacing the established worker, checkpoint, retry, lease, or field-shadow pipeline.
 */
object CausalCognitionTaskObserverRegistry {
    @Volatile
    private var observer: DurableTaskExecutionObserver? = null

    fun install(value: DurableTaskExecutionObserver) {
        synchronized(this) {
            check(observer == null || observer === value) {
                "A different causal cognition task observer is already installed"
            }
            observer = value
        }
    }

    fun current(): DurableTaskExecutionObserver? = observer

    internal fun clearForTests() {
        synchronized(this) {
            observer = null
        }
    }
}
