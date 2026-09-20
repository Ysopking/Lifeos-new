package app.lifeos.core.runtime.personal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WhatsAppStreamingParserTest {
    @Test
    fun `streaming parser keeps multiline text and owner boundary`() {
        val parser = WhatsAppTextArchiveParser(setOf("Tava"))
        val lines = sequenceOf(
            "[20.09.26, 10:01:02] Tava: Erste Zeile",
            "zweite Zeile",
            "[20.09.26, 10:02:03] Alex: Antwort",
        )

        val turns = parser.parseLines(lines, "chat-1").toList()

        assertEquals(2, turns.size)
        assertEquals(PersonalConversationSpeaker.OWNER, turns[0].speaker)
        assertTrue(turns[0].text.contains("zweite Zeile"))
        assertEquals(PersonalConversationSpeaker.OTHER, turns[1].speaker)
        assertEquals("whatsapp-0", turns[0].externalMessageId)
        assertEquals("whatsapp-1", turns[1].externalMessageId)
    }

    @Test
    fun `owner matching is case insensitive and whitespace safe`() {
        val parser = WhatsAppTextArchiveParser(setOf("  TAVA  "))
        val turn = parser.parse(
            "[20.09.26, 10:01] Tava: Hallo",
            "chat-2",
        ).single()

        assertEquals(PersonalConversationSpeaker.OWNER, turn.speaker)
    }
}
