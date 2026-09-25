package app.lifeos.next.ui.chat

import app.lifeos.core.runtime.chat.ChatEvent
import app.lifeos.core.runtime.chat.ChatEventType
import app.lifeos.core.runtime.chat.ChatRole
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatFirstUxPolicyTest {
    @Test
    fun clarificationOptionsAreBoundedCanonicalAndOnlyShownWhenRequired() {
        assertEquals(
            listOf("A", "B", "C", "D"),
            ChatClarificationPolicy.options(
                required = true,
                alternatives = listOf(" A ", "B", "A", "", "C", "D", "E"),
            ),
        )
        assertEquals(
            emptyList(),
            ChatClarificationPolicy.options(
                required = false,
                alternatives = listOf("A"),
            ),
        )
    }

    @Test
    fun primaryTimelineKeepsConversationAndErrorsButHidesOperationalSystemNoise() {
        assertTrue(
            ChatPrimaryTimelinePolicy.visible(
                message(ChatRole.USER, ChatEventType.MESSAGE)
            )
        )
        assertTrue(
            ChatPrimaryTimelinePolicy.visible(
                message(ChatRole.LIFEOS, ChatEventType.MESSAGE)
            )
        )
        assertTrue(
            ChatPrimaryTimelinePolicy.visible(
                message(ChatRole.SYSTEM, ChatEventType.ERROR)
            )
        )
        assertFalse(
            ChatPrimaryTimelinePolicy.visible(
                message(ChatRole.SYSTEM, ChatEventType.MODULE_RESULT)
            )
        )
    }

    private fun message(
        role: ChatRole,
        type: ChatEventType,
    ): ChatTimelineItem.Message = ChatTimelineItem.Message(
        event = ChatEvent(
            id = "event-${role.name}-${type.name}",
            turnId = "turn",
            role = role,
            type = type,
            text = "text",
            createdAt = Instant.parse("2026-09-25T00:00:00Z"),
        )
    )
}
