package app.lifeos.next.kernel

import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.goal.LocalKnowledgeGoalKind

sealed interface LocalKnowledgeExecutionResult {
    data class Produced(
        val kind: LocalKnowledgeGoalKind,
        val output: PhotonSubmissionResult,
        val evidencePhotonIds: List<PhotonId>,
    ) : LocalKnowledgeExecutionResult {
        init {
            require(evidencePhotonIds.distinct().size == evidencePhotonIds.size) {
                "Local knowledge evidence ids must be unique"
            }
        }
    }

    data class Failed(val message: String) : LocalKnowledgeExecutionResult {
        init { require(message.isNotBlank()) }
    }
}
