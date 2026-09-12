package app.lifeos.core.runtime.life

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRepository

/**
 * Narrow repository view for life-memory projection. Direct id access and writes still use the
 * authoritative encrypted repository; only bulk projection hides runtime bookkeeping that is not
 * user/life evidence. Memory-management records stay visible so cursor/access ledgers can rebuild.
 */
class LifeMemoryEvidenceRepository(
    private val delegate: PhotonRepository,
) : PhotonRepository {
    override suspend fun save(photon: Photon) = delegate.save(photon)

    override suspend fun load(id: PhotonId): Photon? = delegate.load(id)

    override suspend fun loadReport(): PhotonLoadReport = delegate.loadReport()

    override suspend fun loadAll(): List<Photon> = delegate.loadAll().filterNot(::isRuntimeBookkeeping)

    override suspend fun delete(id: PhotonId) = delegate.delete(id)

    private fun isRuntimeBookkeeping(photon: Photon): Boolean =
        "internal" in photon.tags ||
            "causal-ledger" in photon.tags ||
            photon.tags.any { tag ->
                tag == "cognition-journal" ||
                    tag.startsWith("cognition-journal-kind:") ||
                    tag.startsWith("cognition-journal-schema:")
            }
}
