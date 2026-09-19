package app.lifeos.next.kernel

import app.lifeos.core.model.Photon

data class PhotonSubmissionResult(
    val photon: Photon,
    val processingQueued: Boolean,
    /** True when Photon + cognition event are durable but TaskStore admission is deferred. */
    val processingDeferred: Boolean = false,
    val processingFailure: String? = null,
)
