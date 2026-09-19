package app.lifeos.core.runtime.world

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-local analysis sink for self-observation WorldFormula runs.
 *
 * This is intentionally not a durable/productive world repository. It satisfies the generic
 * WorldFormula execution contract without allowing self-observation polls to become candidates for
 * productive CognitiveSnapshot capture or ProductiveWorldHead publication.
 */
class SelfStateWorldFormulaSnapshotRepository : WorldFormulaSnapshotRepository {
    private val mutex = Mutex()
    private val byId = linkedMapOf<String, WorldFormulaSnapshot>()

    override suspend fun save(snapshot: WorldFormulaSnapshot) = mutex.withLock {
        val existing = byId[snapshot.id]
        require(existing == null || existing == snapshot) {
            "Self-state WorldFormula snapshot id collision"
        }
        byId[snapshot.id] = snapshot
        while (byId.size > MAX_SNAPSHOTS) {
            byId.remove(byId.keys.first())
        }
    }

    override suspend fun load(id: String): WorldFormulaSnapshot? = mutex.withLock {
        byId[id]
    }

    override suspend fun loadLatest(): WorldFormulaSnapshot? = mutex.withLock {
        byId.values.lastOrNull()
    }

    override suspend fun loadReport(): WorldFormulaSnapshotLoadReport = mutex.withLock {
        WorldFormulaSnapshotLoadReport(
            snapshots = byId.values.toList(),
            unreadableEntries = emptyList(),
        )
    }

    override suspend fun delete(id: String) {
        mutex.withLock { byId.remove(id) }
    }

    private companion object {
        const val MAX_SNAPSHOTS = 16
    }
}
