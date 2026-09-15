package app.lifeos.core.runtime

import app.lifeos.core.model.PhotonRevisionRef

data class CognitiveDecisionTrace(
    val traceId: String,
    val inputs: Set<PhotonRevisionRef>,
    val interpretationIds: Set<String>,
    val dependencyFingerprints: Set<String>,
    val fieldIds: Set<String>,
    val matterIds: Set<String>,
    val goalIds: Set<String>,
    val decisionId: String?,
    val artifactIds: Set<String>,
    val actionIds: Set<String>,
    val outcomeIds: Set<String>,
) {
    init { require(traceId.isNotBlank()) }
}
