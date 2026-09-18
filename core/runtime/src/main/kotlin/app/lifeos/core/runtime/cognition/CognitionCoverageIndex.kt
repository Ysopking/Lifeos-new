package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.PhotonRevisionRef
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CognitionCoverageSnapshot(
    val formatVersion: Int = FORMAT_VERSION,
    val covered: Set<PhotonRevisionRef> = emptySet(),
) {
    init {
        require(formatVersion == FORMAT_VERSION) { "Unsupported cognition coverage format" }
        require(covered.size <= MAX_ENTRIES) { "Cognition coverage index too large" }
    }

    fun canonical(): List<PhotonRevisionRef> =
        covered.sortedWith(
            compareBy<PhotonRevisionRef> { it.photonId.value }
                .thenBy { it.revision }
        )

    companion object {
        const val FORMAT_VERSION = 1
        const val MAX_ENTRIES = 500_000
    }
}

interface CognitionCoverageRepository {
    suspend fun load(): CognitionCoverageSnapshot?
    suspend fun save(snapshot: CognitionCoverageSnapshot)
}

class CognitionCoverageIndex(
    private val repository: CognitionCoverageRepository,
) {
    private val mutex = Mutex()

    @Volatile
    private var cached: CognitionCoverageSnapshot? = null

    suspend fun snapshot(): CognitionCoverageSnapshot = mutex.withLock {
        currentLocked()
    }

    suspend fun contains(ref: PhotonRevisionRef): Boolean = mutex.withLock {
        ref in currentLocked().covered
    }

    suspend fun markCovered(ref: PhotonRevisionRef) = markCovered(listOf(ref))

    suspend fun markCovered(refs: Collection<PhotonRevisionRef>) = mutex.withLock {
        if (refs.isEmpty()) return@withLock
        val current = currentLocked()
        val updated = current.copy(covered = current.covered + refs)
        repository.save(updated)
        cached = updated
    }

    suspend fun retainOnly(refs: Set<PhotonRevisionRef>) = mutex.withLock {
        val current = currentLocked()
        val updated = current.copy(covered = current.covered.intersect(refs))
        if (updated != current) repository.save(updated)
        cached = updated
    }

    private suspend fun currentLocked(): CognitionCoverageSnapshot {
        cached?.let { return it }
        return (repository.load() ?: CognitionCoverageSnapshot()).also { cached = it }
    }
}
