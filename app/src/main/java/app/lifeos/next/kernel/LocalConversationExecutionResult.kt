package app.lifeos.next.kernel

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.goal.LocalConversationMove

sealed interface LocalConversationExecutionResult {
    data class Produced(
        val move: LocalConversationMove,
        val photon: Photon,
        val evidencePhotonIds: List<PhotonId>,
    ) : LocalConversationExecutionResult {
        init {
            require(evidencePhotonIds.distinct().size == evidencePhotonIds.size)
            require("local-conversation-response" in photon.tags) {
                "Conversation execution output must be a local conversation response Photon"
            }
        }
    }

    data class Failed(val message: String) : LocalConversationExecutionResult {
        init { require(message.isNotBlank()) }
    }
}

/**
 * Bounded process-local bridge between action execution and the owner-visible response composer.
 * Durable truth stays in the Photon store; this registry only avoids widening the kernel return
 * envelope while one live chat turn is being completed.
 */
object ConversationExecutionResultRegistry {
    private const val MAX_RESULTS = 64
    private val results = linkedMapOf<PhotonId, LocalConversationExecutionResult>()

    @Synchronized
    fun publish(goalPhotonId: PhotonId, result: LocalConversationExecutionResult) {
        results.remove(goalPhotonId)
        results[goalPhotonId] = result
        while (results.size > MAX_RESULTS) {
            val eldest = results.keys.firstOrNull() ?: break
            results.remove(eldest)
        }
    }

    @Synchronized
    fun current(goalPhotonId: PhotonId): LocalConversationExecutionResult? = results[goalPhotonId]

    @Synchronized
    fun clear() {
        results.clear()
    }
}
