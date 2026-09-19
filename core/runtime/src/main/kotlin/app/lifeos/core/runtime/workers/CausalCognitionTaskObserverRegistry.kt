package app.lifeos.core.runtime.workers

/**
 * Non-owning process seam that lets causal cognition observe durable task completion.
 *
 * Re-installation intentionally replaces the previous process object. The observer owns no durable
 * task/checkpoint state and must be reconstructed from composition after process recreation.
 */
object CausalCognitionTaskObserverRegistry {
    private val slot =
        app.lifeos.core.runtime.process.NonOwningRuntimeSlot<DurableTaskExecutionObserver>(
            "Causal cognition task observer"
        )

    fun install(value: DurableTaskExecutionObserver) {
        slot.install(value)
    }

    fun current(): DurableTaskExecutionObserver? = slot.currentOrNull()

    internal fun clearForTests() {
        slot.clear()
    }
}
