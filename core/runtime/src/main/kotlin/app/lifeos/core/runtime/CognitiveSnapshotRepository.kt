package app.lifeos.core.runtime

/** Persistence boundary for recoverable cognitive snapshots; events remain the historical source of truth. */
interface CognitiveSnapshotRepository {
    suspend fun save(snapshot: CognitiveSnapshot)

    /** Newest-first candidates. Callers must verify every snapshot/manifest pair before use. */
    suspend fun candidates(limit: Int = 4): List<Pair<CognitiveSnapshot, SnapshotManifest>>
}
