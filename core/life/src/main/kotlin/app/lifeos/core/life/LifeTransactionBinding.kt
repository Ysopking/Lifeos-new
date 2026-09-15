package app.lifeos.core.life

import app.lifeos.core.model.CognitiveTransactionId

data class LifeTransactionBinding(
    val transactionId: CognitiveTransactionId,
    val sourceDeltaIds: List<String>,
    val matterIds: List<String>,
    val domainIds: List<LifeDomainId>,
    val attentionIds: List<String>,
) {
    init {
        require(sourceDeltaIds.none { it.isBlank() } && sourceDeltaIds.distinct().size == sourceDeltaIds.size)
        require(matterIds.none { it.isBlank() } && matterIds.distinct().size == matterIds.size)
        require(domainIds.distinct().size == domainIds.size)
        require(attentionIds.none { it.isBlank() } && attentionIds.distinct().size == attentionIds.size)
    }
}
