package app.lifeos.next.kernel

import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.goal.LocalConversationMove

sealed interface LocalConversationExecutionResult {
    data class Produced(
        val move: LocalConversationMove,
        val output: PhotonSubmissionResult,
        val evidencePhotonIds: List<PhotonId>,
    ) : LocalConversationExecutionResult {
        init {
            require(evidencePhotonIds.distinct().size == evidencePhotonIds.size)
        }
    }

    data class Failed(val message: String) : LocalConversationExecutionResult {
        init { require(message.isNotBlank()) }
    }
}
