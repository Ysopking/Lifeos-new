package app.lifeos.core.runtime

import app.lifeos.core.model.FieldInfluence
import app.lifeos.core.model.PhotonId

data class RuntimeState(
    val status: RuntimeStatus = RuntimeStatus.CREATED,
    val processed: Long = 0,
    val lastPhotonId: PhotonId? = null,
    val lastFailure: RuntimeFailure? = null,
    val failed: Long = 0,
    val recentInfluences: List<FieldInfluence> = emptyList(),
) {
    val running: Boolean
        get() = status == RuntimeStatus.RUNNING

    val lastError: String?
        get() = lastFailure?.message
}
