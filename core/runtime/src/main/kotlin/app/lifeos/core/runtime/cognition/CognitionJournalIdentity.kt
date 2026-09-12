package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.StableCognitiveIds

/** Stable, replay-safe Photon ids for durable cognition journal records. */
internal object CognitionJournalIdentity {
    fun photonId(kind: String, stableId: String): PhotonId {
        require(kind.isNotBlank())
        require(stableId.isNotBlank())
        return PhotonId("cogj-" + StableCognitiveIds.fingerprint("cognition-journal-id/v1", kind, stableId))
    }
}
