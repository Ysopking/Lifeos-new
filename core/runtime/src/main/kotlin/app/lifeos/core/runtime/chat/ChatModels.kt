package app.lifeos.core.runtime.chat

import app.lifeos.core.model.PhotonId
import java.time.Instant

enum class ChatRole {
    USER,
    LIFEOS,
    SYSTEM,
}

enum class ChatEventType {
    MESSAGE,
    THINKING,
    MODULE_STARTED,
    MODULE_RESULT,
    TOOL_STARTED,
    TOOL_RESULT,
    ARTIFACT,
    RECOVERY,
    ERROR,
}

data class ChatEvent(
    val id: String,
    val turnId: String,
    val role: ChatRole,
    val type: ChatEventType,
    val text: String? = null,
    val photonId: PhotonId? = null,
    val sourceModule: String? = null,
    val createdAt: Instant,
) {
    init {
        require(id.isNotBlank()) { "Chat event id must not be blank" }
        require(turnId.isNotBlank()) { "Chat turn id must not be blank" }
        require(text == null || text.isNotBlank()) { "Chat event text must be null or non-blank" }
    }
}
