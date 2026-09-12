package app.lifeos.next.kernel

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.runtime.PhotonIngressMode

/**
 * Repository boundary for the productive life-memory subsystem.
 *
 * Authoritative source evidence/state and generated memory projections become live/durable runtime
 * Photons. Pure persistence-management records (source checkpoints and memory-access events) remain
 * repository-only so cursor/accounting state cannot accidentally seed cognition.
 */
internal class CanonicalLifePhotonRepository(
    private val delegate: PhotonRepository,
    private val productiveIngress: suspend (Photon, PhotonIngressMode) -> Unit,
) : PhotonRepository {
    override suspend fun save(photon: Photon) {
        val mode = productiveMode(photon)
        if (mode == null) {
            delegate.save(photon)
        } else {
            productiveIngress(photon, mode)
        }
    }

    override suspend fun load(id: PhotonId): Photon? = delegate.load(id)

    override suspend fun loadReport(): PhotonLoadReport = delegate.loadReport()

    override suspend fun loadAll(): List<Photon> = delegate.loadAll()

    override suspend fun delete(id: PhotonId) = delegate.delete(id)

    /**
     * Reconciles productive life Photons that may have been persisted by an older build or by a
     * process that died between repository persistence and durable cognitive submission.
     */
    suspend fun reconcilePersisted(): Int {
        var reconciled = 0
        delegate.loadAll()
            .sortedWith(compareBy<Photon> { it.provenance.createdAt }.thenBy { it.id.value }.thenBy { it.revision })
            .forEach { photon ->
                val mode = productiveMode(photon) ?: return@forEach
                productiveIngress(photon, mode)
                reconciled += 1
            }
        return reconciled
    }

    internal companion object {
        fun productiveMode(photon: Photon): PhotonIngressMode? = when {
            "life-source-evidence" in photon.tags -> PhotonIngressMode.ORIGIN
            "life-source-gap" in photon.tags -> PhotonIngressMode.ORIGIN
            "memory-atom" in photon.tags -> PhotonIngressMode.DERIVED
            "memory-crystal" in photon.tags -> PhotonIngressMode.DERIVED
            "initial-data-bootstrap" in photon.tags -> PhotonIngressMode.DERIVED
            else -> null
        }
    }
}
