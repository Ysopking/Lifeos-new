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
    val matterId: LifeMatterId,
    val domainId: LifeDomainId,
    val title: String,
    val state: LifeMatterState,
    val revision: Long,
    val photonRevisions: Set<PhotonRevisionRef>,
    val relationMatterIds: Set<LifeMatterId> = emptySet(),
) {
    init {
        require(matterId.value.isNotBlank())
        require(title.isNotBlank())
        require(revision > 0)
        require(relationMatterIds.none { it.value.isBlank() })
        require(matterId !in relationMatterIds)
    }

    /** Stable compatibility view for indexes that still persist string keys. */
    val photonRevisionKeys: Set<String> get() = photonRevisions.mapTo(linkedSetOf()) { it.stableKey }
}
