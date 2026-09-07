package app.lifeos.next.kernel

import app.lifeos.core.model.Photon

data class PhotonSubmissionResult(
    val photon: Photon,
    val processingQueued: Boolean,
    val processingFailure: String? = null,
)
