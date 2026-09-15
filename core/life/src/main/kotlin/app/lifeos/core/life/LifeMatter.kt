package app.lifeos.core.life

import app.lifeos.core.model.PhotonRevisionRef

enum class LifeMatterState {
    OPEN,
    ACTION_REQUIRED,
    WAITING,
    RESOLVED,
    ARCHIVED,
}

data class LifeMatter(
    val matterId: String,
    val domainId: LifeDomainId,
    val title: String,
    val state: LifeMatterState,
    val revision: Long,
    val photonRevisions: Set<PhotonRevisionRef>,
    val relationMatterIds: Set<String> = emptySet(),
) {
    init {
        require(matterId.isNotBlank())
        require(title.isNotBlank())
        require(revision > 0)
        require(relationMatterIds.none { it.isBlank() })
        require(matterId !in relationMatterIds)
    }

    /** Stable compatibility view for indexes that still persist string keys. */
    val photonRevisionKeys: Set<String> get() = photonRevisions.mapTo(linkedSetOf()) { it.stableKey }
}
