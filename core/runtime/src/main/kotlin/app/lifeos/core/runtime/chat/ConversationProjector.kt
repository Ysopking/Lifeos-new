package app.lifeos.core.runtime.chat

import app.lifeos.core.model.Photon

object ConversationProjector {
    fun project(
        photons: Iterable<Photon>,
        conversationId: String = DEFAULT_CONVERSATION_ID,
    ): List<ChatEvent> {
        require(conversationId.isNotBlank()) { "Conversation id must not be blank" }
        return photons
            .asSequence()
            .filter { "chat" in it.tags }
            .filter { photon ->
                val conversationTag = photon.tags.firstOrNull { it.startsWith(CONVERSATION_PREFIX) }
                conversationTag == null || conversationTag == "$CONVERSATION_PREFIX$conversationId"
            }
            .map { photon ->
                ChatEvent(
                    id = "${photon.id.value}:${photon.revision}",
                    turnId = photon.tags
                        .firstOrNull { it.startsWith(TURN_PREFIX) }
                        ?.removePrefix(TURN_PREFIX)
                        ?.takeIf { it.isNotBlank() }
                        ?: photon.id.value,
                    role = photon.role(),
                    type = ChatEventType.MESSAGE,
                    text = photon.content,
                    photonId = photon.id,
                    sourceModule = photon.provenance.source,
                    createdAt = photon.provenance.createdAt,
                )
            }
            .sortedWith(compareBy<ChatEvent>({ it.createdAt }, { it.id }))
            .toList()
    }

    private fun Photon.role(): ChatRole = when {
        "chat:assistant" in tags || provenance.actor.equals("lifeos", ignoreCase = true) -> ChatRole.LIFEOS
        "chat:user" in tags || provenance.actor.equals("user", ignoreCase = true) -> ChatRole.USER
        else -> ChatRole.SYSTEM
    }

    const val DEFAULT_CONVERSATION_ID = "default"
    const val CONVERSATION_PREFIX = "conversation:"
    const val TURN_PREFIX = "turn:"
}
