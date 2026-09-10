package app.lifeos.next.kernel

import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.goal.LocalKnowledgeGoalKind

data class LocalKnowledgeExecutionResult(
    val kind: LocalKnowledgeGoalKind,
    val output: PhotonSubmissionResult,
    val evidencePhotonIds: List<PhotonId>,
) {
    init {
        require(evidencePhotonIds.distinct().size == evidencePhotonIds.size) {
            "Local knowledge evidence ids must be unique"
        }
    }
}
