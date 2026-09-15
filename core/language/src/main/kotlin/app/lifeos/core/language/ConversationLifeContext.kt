package app.lifeos.core.language

import app.lifeos.core.model.PhotonId

data class ConversationLifeContext(
    val activeThread: String,
    val activeDomains: Set<String> = emptySet(),
    val activeMatters: Set<String> = emptySet(),
    val activeEntities: Set<PhotonId> = emptySet(),
    val activeArtifacts: Set<PhotonId> = emptySet(),
    val recentReferenceBindings: Map<String, PhotonId> = emptyMap(),
    val unresolvedReferences: Set<String> = emptySet(),
) {
    init { require(activeThread.isNotBlank()) }

    fun bind(expression: String, target: PhotonId): ConversationLifeContext = copy(
        recentReferenceBindings = recentReferenceBindings + (expression to target),
        unresolvedReferences = unresolvedReferences - expression,
    )

    fun unresolved(expression: String): ConversationLifeContext = copy(
        unresolvedReferences = unresolvedReferences + expression,
    )
}
