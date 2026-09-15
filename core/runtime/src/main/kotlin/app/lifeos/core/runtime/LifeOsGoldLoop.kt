package app.lifeos.core.runtime

import app.lifeos.core.model.PhotonRevisionRef

/** B100 contract: one closed information-to-outcome loop without parallel truth authority. */
data class LifeOsGoldLoopState(
    val observationRefs: Set<PhotonRevisionRef>,
    val coupledRefs: Set<PhotonRevisionRef>,
    val activeMatterIds: Set<String>,
    val worldRevision: Long,
    val responsePlanIds: Set<String>,
    val artifactIds: Set<String>,
    val actionContractIds: Set<String>,
    val outcomeIds: Set<String>,
    val learnedRefs: Set<PhotonRevisionRef>,
) {
    init { require(worldRevision >= 0) }

    val hasClosedOutcomeLoop: Boolean
        get() = outcomeIds.isNotEmpty() && learnedRefs.isNotEmpty()
}

object LifeOsGoldInvariants {
    const val SOURCE_NOT_INTERPRETATION = "source!=interpretation"
    const val GENERATED_NOT_INDEPENDENT_EVIDENCE = "generated!=independent_evidence"
    const val DOMAIN_PROJECTION_NOT_DATA_COPY = "domain_projection!=domain_data_copy"
    const val UTILITY_NOT_AUTHORITY = "utility!=authority"
    const val HARDWARE_BUDGET_NOT_TRUTH = "hardware_budget!=truth"
    const val CONVERSATION_PREEMPTS_BACKGROUND = "conversation_preempts_background"
    const val AUTHORITY_CANNOT_SELF_EVOLVE = "authority_cannot_self_evolve"

    val all: Set<String> = linkedSetOf(
        SOURCE_NOT_INTERPRETATION,
        GENERATED_NOT_INDEPENDENT_EVIDENCE,
        DOMAIN_PROJECTION_NOT_DATA_COPY,
        UTILITY_NOT_AUTHORITY,
        HARDWARE_BUDGET_NOT_TRUTH,
        CONVERSATION_PREEMPTS_BACKGROUND,
        AUTHORITY_CANNOT_SELF_EVOLVE,
    )
}
