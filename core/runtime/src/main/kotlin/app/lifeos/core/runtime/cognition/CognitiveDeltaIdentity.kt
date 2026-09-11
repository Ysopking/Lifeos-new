package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.PhotonId

/** Stable identity shared by live ingestion and crash-recovery reconciliation. */
object CognitiveDeltaIdentity {
    fun photonRevision(photonId: PhotonId, revision: Long): String {
        require(photonId.value.isNotBlank()) { "Photon id must not be blank" }
        require(revision > 0) { "Photon revision must be positive" }
        return "photon:${photonId.value}:revision:$revision"
    }
}
