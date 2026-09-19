package app.lifeos.next.ui.chat

import app.lifeos.core.image.ImagePhotonFactory
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.chat.ChatEvent
import app.lifeos.core.runtime.chat.ConversationProjector
import java.time.Instant

sealed interface ChatTimelineItem {
    val id: String
    val createdAt: Instant

    data class Message(
        val event: ChatEvent,
    ) : ChatTimelineItem {
        override val id: String = "message:${event.id}"
        override val createdAt: Instant = event.createdAt
    }

    data class Image(
        val photon: Photon,
    ) : ChatTimelineItem {
        override val id: String = "image:${photon.id.value}:${photon.revision}"
        override val createdAt: Instant = photon.provenance.createdAt
    }
}

object ChatTimelineProjector {
    private const val MAX_ANCESTRY_DEPTH = 12

    fun project(
        photons: Iterable<Photon>,
        conversationId: String = ConversationProjector.DEFAULT_CONVERSATION_ID,
    ): List<ChatTimelineItem> {
        require(conversationId.isNotBlank()) { "Conversation id must not be blank" }
        val snapshot = photons.toList()
        val byId = snapshot.associateBy { it.id }
        val messages = ConversationProjector.project(snapshot, conversationId)
            .map(ChatTimelineItem::Message)
        val conversationTag = "${ConversationProjector.CONVERSATION_PREFIX}$conversationId"
        val images = snapshot.asSequence()
            .filter { it.mimeType == ImagePhotonFactory.IMAGE_REFERENCE_MIME }
            .filter { image ->
                conversationTag in image.tags ||
                    reachesConversationUser(
                        parentIds = image.provenance.parentIds,
                        byId = byId,
                        conversationId = conversationId,
                    )
            }
            .map(ChatTimelineItem::Image)
            .toList()

        return (messages + images).sortedWith(
            compareBy<ChatTimelineItem>(
                { it.createdAt },
                { kindOrder(it) },
                { it.id },
            )
        )
    }

    private fun reachesConversationUser(
        parentIds: Set<PhotonId>,
        byId: Map<PhotonId, Photon>,
        conversationId: String,
    ): Boolean {
        val stack = ArrayDeque<Pair<PhotonId, Int>>()
        parentIds.forEach { stack.addLast(it to 1) }
        val visited = mutableSetOf<PhotonId>()
        val conversationTag = "${ConversationProjector.CONVERSATION_PREFIX}$conversationId"

        while (stack.isNotEmpty()) {
            val (id, depth) = stack.removeLast()
            if (!visited.add(id) || depth > MAX_ANCESTRY_DEPTH) continue
            val photon = byId[id] ?: continue
            if (
                "chat" in photon.tags &&
                "chat:user" in photon.tags &&
                conversationTag in photon.tags
            ) {
                return true
            }
            if (depth < MAX_ANCESTRY_DEPTH) {
                photon.provenance.parentIds.forEach { parent ->
                    if (parent !in visited) stack.addLast(parent to depth + 1)
                }
            }
        }
        return false
    }

    private fun kindOrder(item: ChatTimelineItem): Int = when (item) {
        is ChatTimelineItem.Message -> 0
        is ChatTimelineItem.Image -> 1
    }
}
