package app.lifeos.core.runtime.life

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonRepository

/**
 * Rebuildable relevance bridge. Existing goal/future evidence emits stable access events pointing
 * back to its immutable source Photons. Re-running at boot is idempotent because each relation uses
 * a stable access key derived from the evidence Photon/scenario identity.
 */
class LifeMemoryRelevanceBridge(
    photons: PhotonRepository,
    private val access: PhotonBackedMemoryAccessLedgerStore = PhotonBackedMemoryAccessLedgerStore(photons),
) {
    suspend fun bind(persisted: Collection<Photon>) {
        persisted.sortedBy { it.id.value }.forEach { photon ->
            bindFuture(photon)
            bindGoal(photon)
        }
    }

    private suspend fun bindFuture(photon: Photon) {
        val scenario = FutureEvidencePhotonCodec.decode(photon) ?: return
        scenario.sourcePhotonIds.sortedBy { it.value }.forEach { sourceId ->
            access.recordAccess(
                photonId = sourceId,
                accessKey = "future:${scenario.id}:source:${sourceId.value}",
                at = photon.provenance.createdAt,
                futureRelevance = scenario.probability,
            )
        }
    }

    private suspend fun bindGoal(photon: Photon) {
        if (photon.tags.none { it == "goal" || it.startsWith("goal:") }) return
        if (photon.provenance.parentIds.isEmpty()) return
        val relevance = maxOf(photon.confidence, photon.semanticMass).coerceIn(0.0, 1.0)
        photon.provenance.parentIds.sortedBy { it.value }.forEach { sourceId ->
            access.recordAccess(
                photonId = sourceId,
                accessKey = "goal:${photon.id.value}:source:${sourceId.value}",
                at = photon.provenance.createdAt,
                goalRelevance = relevance,
            )
        }
    }
}
