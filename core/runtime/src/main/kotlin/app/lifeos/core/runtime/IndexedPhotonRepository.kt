package app.lifeos.core.runtime

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Optional process-cache control; it changes residency only, never persistent truth. */
interface PhotonResidencyController {
    suspend fun retainResident(ids: Set<PhotonId>)
}

/**
 * Process-local read-through index over the authoritative PhotonRepository.
 *
 * The delegate remains persistence authority. A complete loadReport can be reused until a memory
 * tier owner explicitly demotes entries via [retainResident]. After demotion the cache is partial:
 * cache misses go to the encrypted delegate and another full report must be rebuilt explicitly.
 */
class IndexedPhotonRepository(
    private val delegate: PhotonRepository,
) : PhotonRepository, PhotonResidencyController {
    private val mutex = Mutex()
    private var snapshot: Snapshot? = null

    override suspend fun save(photon: Photon) = mutex.withLock {
        val current = snapshot
        current?.byId?.get(photon.id)?.let { previous ->
            require(previous.revision <= photon.revision) {
                "Indexed Photon revision regressed for ${photon.id.value}"
            }
            if (previous.revision == photon.revision) {
                require(previous == photon) {
                    "Conflicting Photon state for ${photon.id.value}@${photon.revision}"
                }
                return@withLock
            }
        }

        delegate.save(photon)
        val after = snapshot ?: return@withLock
        if (after.unreadableFiles.isNotEmpty()) {
            snapshot = null
            return@withLock
        }
        snapshot = after.copy(byId = after.byId + (photon.id to photon))
    }

    override suspend fun load(id: PhotonId): Photon? = mutex.withLock {
        val current = snapshot
        current?.byId?.get(id)?.let { return@withLock it }
        if (current?.complete == true && current.unreadableFiles.isEmpty()) {
            return@withLock null
        }
        delegate.load(id)
    }

    override suspend fun loadAll(): List<Photon> = loadReport().also { report ->
        check(report.unreadableFiles.isEmpty()) {
            "Unreadable photons: ${report.unreadableFiles.size}"
        }
    }.photons

    override suspend fun loadReport(): PhotonLoadReport = mutex.withLock {
        val current = snapshot
        if (current?.complete == true) return@withLock current.toReport()

        val report = delegate.loadReport()
        val byId = linkedMapOf<PhotonId, Photon>()
        report.photons.forEach { photon ->
            val previous = byId.put(photon.id, photon)
            require(previous == null) {
                "Photon load report contains duplicate id ${photon.id.value}"
            }
        }
        val loaded = Snapshot(
            byId = byId,
            unreadableFiles = report.unreadableFiles.distinct().sorted(),
            complete = true,
        )
        snapshot = loaded
        loaded.toReport()
    }

    override suspend fun delete(id: PhotonId) = mutex.withLock {
        delegate.delete(id)
        val current = snapshot ?: return@withLock
        if (current.unreadableFiles.isNotEmpty()) {
            snapshot = null
            return@withLock
        }
        snapshot = current.copy(byId = current.byId - id)
    }

    override suspend fun retainResident(ids: Set<PhotonId>) = mutex.withLock {
        val current = snapshot ?: return@withLock
        if (current.unreadableFiles.isNotEmpty()) {
            snapshot = null
            return@withLock
        }
        snapshot = current.copy(
            byId = current.byId.filterKeys(ids::contains),
            complete = false,
        )
    }

    suspend fun invalidate() = mutex.withLock {
        snapshot = null
    }

    suspend fun cachedCount(): Int = mutex.withLock {
        snapshot?.byId?.size ?: 0
    }

    private data class Snapshot(
        val byId: Map<PhotonId, Photon>,
        val unreadableFiles: List<String>,
        val complete: Boolean,
    ) {
        fun toReport(): PhotonLoadReport {
            require(complete) { "Partial Photon cache cannot masquerade as a full load report" }
            return PhotonLoadReport(
                photons = byId.values.sortedBy { it.provenance.createdAt },
                unreadableFiles = unreadableFiles,
            )
        }
    }
}
