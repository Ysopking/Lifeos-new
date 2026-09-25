package app.lifeos.next.ui.chat

import app.lifeos.core.runtime.chat.ChatEventType
import app.lifeos.core.runtime.chat.ChatRole

/**
 * Keeps the primary conversation human-facing.
 *
 * Operational lifecycle chatter belongs in the System surface. The chat keeps user/LIFEOS
 * conversation plus system errors that require immediate attention.
 */
internal object ChatPrimaryTimelinePolicy {
    fun visible(item: ChatTimelineItem): Boolean = when (item) {
        is ChatTimelineItem.Image -> true
        is ChatTimelineItem.Message ->
            item.event.role != ChatRole.SYSTEM ||
                item.event.type == ChatEventType.ERROR
    }
}
