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
            .filter { it.conversationVisible() }
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
                        ?: photon.provenance.parentIds.firstOrNull()?.value
                        ?: photon.id.value,
                    role = photon.role(),
                    type = photon.eventType(),
                    text = photon.conversationText(),
                    photonId = photon.id,
                    sourceModule = photon.provenance.source,
                    createdAt = photon.provenance.createdAt,
                )
            }
            .sortedWith(compareBy<ChatEvent>({ it.createdAt }, { it.id }))
            .toList()
    }

    private fun Photon.conversationVisible(): Boolean =
        "chat" in tags ||
            "genesis-proposal" in tags ||
            "autonomous-request" in tags ||
            "tool-workshop-outcome" in tags ||
            "evolution-handoff" in tags

    private fun Photon.role(): ChatRole = when {
        "genesis-proposal" in tags ||
            "autonomous-request" in tags ||
            "tool-workshop-outcome" in tags ||
            "system:health" in tags ||
            "system:hot-swap" in tags ||
            "system:evolution" in tags -> ChatRole.SYSTEM
        "chat:assistant" in tags || provenance.actor.equals("lifeos", ignoreCase = true) -> ChatRole.LIFEOS
        "chat:user" in tags || provenance.actor.equals("user", ignoreCase = true) -> ChatRole.USER
        else -> ChatRole.SYSTEM
    }

    private fun Photon.eventType(): ChatEventType = when {
        "genesis-proposal" in tags -> ChatEventType.MODULE_STARTED
        "autonomous-request" in tags -> ChatEventType.TOOL_STARTED
        "tool-workshop-outcome" in tags || "evolution-handoff" in tags -> ChatEventType.TOOL_RESULT
        "system:health" in tags -> ChatEventType.RECOVERY
        "system:hot-swap" in tags || "system:evolution" in tags -> ChatEventType.MODULE_RESULT
        else -> ChatEventType.MESSAGE
    }

    private fun Photon.conversationText(): String = when {
        "genesis-proposal" in tags -> {
            val capability = field("capability") ?: "unbekannte Fähigkeit"
            val solution = field("solution") ?: "UNKNOWN"
            val target = field("target") ?: "UNKNOWN"
            val approval = field("requiresExplicitApproval") == "true"
            buildString {
                append("Genesis: ").append(capability)
                    .append(" → ").append(solution)
                    .append(" → ").append(target)
                if (approval) append(" · Freigabe erforderlich")
            }
        }
        "autonomous-request" in tags -> {
            val capability = field("capability") ?: "unbekannte Fähigkeit"
            "Autonomer ToolWorkshop gestartet: $capability wird als fehlende Fähigkeit bearbeitet."
        }
        "tool-workshop-outcome" in tags -> {
            val capability = field("capability") ?: "Fähigkeit"
            val state = field("state") ?: "UNKNOWN"
            val route = field("route") ?: "NONE"
            val detail = field("detail")?.takeIf(String::isNotBlank)
            buildString {
                append("ToolWorkshop: ").append(capability).append(" → ").append(state)
                if (route != "NONE") append(" · Evolution: ").append(route)
                if (detail != null) append(" · ").append(detail.take(180))
            }
        }
        else -> content
    }

    private fun Photon.field(name: String): String? = content
        .lineSequence()
        .firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')
        ?.trim()
        ?.takeIf(String::isNotBlank)

    const val DEFAULT_CONVERSATION_ID = "default"
    const val CONVERSATION_PREFIX = "conversation:"
    const val TURN_PREFIX = "turn:"
}
