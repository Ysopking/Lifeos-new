package app.lifeos.next.kernel

import app.lifeos.core.model.Photon

data class PhotonSubmissionResult(
    val photon: Photon,
    /**
     * True when cognition has a durable processing obligation for this exact Photon revision.
     *
     * Normally that means a TaskStore task already exists. Under bounded backpressure it may mean
     * [processingDeferred] instead: the authoritative Photon remains uncovered in the durable
     * cognition coverage index and is therefore guaranteed to be picked up by reconciliation as
     * worker capacity becomes available.
     */
    val processingQueued: Boolean,
    val processingFailure: String? = null,
    val processingDeferred: Boolean = false,
) {
    init {
        require(!processingDeferred || processingQueued) {
            "Deferred cognition must still represent a durable processing obligation"
        }
        require(!processingDeferred || processingFailure == null) {
            "Deferred cognition cannot simultaneously report a processing failure"
        }
    }
}
