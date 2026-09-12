package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId

/** Stable identity and event payload shared by live ingestion and crash-recovery reconciliation. */
object CognitiveDeltaIdentity {
    const val PHOTON_REVISION_SOURCE = "photon-revision"

    fun photonRevision(photonId: PhotonId, revision: Long): String {
        require(photonId.value.isNotBlank()) { "Photon id must not be blank" }
        require(revision > 0) { "Photon revision must be positive" }
        return "photon:${photonId.value}:revision:$revision"
    }

    /**
     * Produces one canonical durable event for a Photon revision. The payload is independent of
     * whether it was observed live, replayed in-process, or repaired by cold-start reconciliation.
     * This keeps the persistent event journal idempotent across every ingress path.
     */
    fun photonRevisionDelta(photon: Photon): PhotonDelta = PhotonDelta(
        deltaId = photonRevision(photon.id, photon.revision),
        source = PHOTON_REVISION_SOURCE,
        photonId = photon.id,
        revisionBefore = null,
        revisionAfter = photon.revision,
        type = if (photon.revision == 1L) PhotonDeltaType.CREATED else PhotonDeltaType.UPDATED,
        importanceHint = photon.semanticMass,
        timestamp = photon.provenance.createdAt,
        correlationId = photon.id.value,
    )
}
