package app.lifeos.core.runtime

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-local read-through index over the authoritative PhotonRepository.
 *
 * The delegate remains persistence authority. A full snapshot is loaded at most once per process
 * while the cache stays healthy; exact saves/deletes update the same snapshot atomically. If the
 * underlying vault reports unreadable files the cache is never trusted across mutations.
 */
class IndexedPhotonRepository(
    private val delegate: PhotonRepository,
) : PhotonRepository {
    private val mutex = Mutex()
    private var snapshot: Snapshot? = null

    override suspend fun save(photon: Photon) = mutex.withLock {
        delegate.save(photon)
        val current = snapshot ?: return@withLock
        if (current.unreadableFiles.isNotEmpty()) {
            snapshot = null
            return@withLock
        }
        val previous = current.byId[photon.id]
        require(previous == null || previous.revision <= photon.revision) {
            "Indexed Photon revision regressed for ${photon.id.value}"
        }
        snapshot = current.copy(byId = current.byId + (photon.id to photon))
    }

    override suspend fun load(id: PhotonId): Photon? = mutex.withLock {
        snapshot?.byId?.get(id) ?: delegate.load(id)
    }

    override suspend fun loadAll(): List<Photon> = loadReport().also { report ->
        check(report.unreadableFiles.isEmpty()) {
            "Unreadable photons: ${report.unreadableFiles.size}"
        }
    }.photons

    override suspend fun loadReport(): PhotonLoadReport = mutex.withLock {
        val current = snapshot
        if (current != null) return@withLock current.toReport()

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

    suspend fun invalidate() = mutex.withLock {
        snapshot = null
    }

    suspend fun cachedCount(): Int = mutex.withLock {
        snapshot?.byId?.size ?: 0
    }

    private data class Snapshot(
        val byId: Map<PhotonId, Photon>,
        val unreadableFiles: List<String>,
    ) {
        fun toReport(): PhotonLoadReport = PhotonLoadReport(
            photons = byId.values.sortedBy { it.provenance.createdAt },
            unreadableFiles = unreadableFiles,
        )
    }
}
