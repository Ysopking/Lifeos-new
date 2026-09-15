package app.lifeos.core.runtime.module

sealed interface ModuleEpistemicLoadResult {
    data object Missing : ModuleEpistemicLoadResult
    data class Loaded(val state: ModuleEpistemicState) : ModuleEpistemicLoadResult
    data class Unreadable(val message: String) : ModuleEpistemicLoadResult {
        init { require(message.isNotBlank()) }
    }
}

sealed interface ModuleEpistemicWriteResult {
    data class Saved(val state: ModuleEpistemicState) : ModuleEpistemicWriteResult
    data class Conflict(val actualRevision: Long?) : ModuleEpistemicWriteResult
    data class UnreadableExisting(val message: String) : ModuleEpistemicWriteResult {
        init { require(message.isNotBlank()) }
    }
}

/**
 * Compare-and-set persistence contract for one module's current epistemic state.
 * Implementations must preserve the revision/predecessor chain and must never silently overwrite a
 * different state at the same revision.
 */
interface ModuleEpistemicStateRepository {
    suspend fun load(moduleId: String): ModuleEpistemicLoadResult

    suspend fun compareAndSet(
        moduleId: String,
        expectedRevision: Long?,
        next: ModuleEpistemicState,
    ): ModuleEpistemicWriteResult
}
