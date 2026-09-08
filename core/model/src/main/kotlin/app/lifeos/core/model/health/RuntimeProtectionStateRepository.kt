package app.lifeos.core.model.health

sealed interface ProtectionStateLoadResult {
    data object Missing : ProtectionStateLoadResult
    data class Loaded(val state: RuntimeProtectionState) : ProtectionStateLoadResult
    data class Unreadable(val message: String) : ProtectionStateLoadResult {
        init { require(message.isNotBlank()) { "Unreadable protection-state message must not be blank" } }
    }
}

sealed interface ProtectionStateWriteResult {
    data class Saved(val state: RuntimeProtectionState) : ProtectionStateWriteResult
    data class Conflict(val actualRevision: Long?) : ProtectionStateWriteResult
    data class UnreadableExisting(val message: String) : ProtectionStateWriteResult {
        init { require(message.isNotBlank()) { "Unreadable protection-state message must not be blank" } }
    }
}

interface RuntimeProtectionStateRepository {
    suspend fun load(): ProtectionStateLoadResult

    /**
     * Generation-safe compare-and-set. [expectedRevision] must be null only when the store is
     * missing. An unreadable existing value must never be silently replaced.
     */
    suspend fun compareAndSet(
        expectedRevision: Long?,
        next: RuntimeProtectionState,
    ): ProtectionStateWriteResult
}
