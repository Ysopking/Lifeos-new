package app.lifeos.core.runtime

import app.lifeos.core.model.PhotonId

enum class RuntimeFailureCategory {
    FIELD,
    TIMEOUT,
    STORAGE,
    INVARIANT,
    UNKNOWN,
}

data class RuntimeFailure(
    val category: RuntimeFailureCategory,
    val source: String,
    val message: String,
    val recoverable: Boolean = true,
    val photonId: PhotonId? = null,
)
