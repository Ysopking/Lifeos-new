package app.lifeos.core.runtime.personal

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersonalCorpusSpeakerBoundaryTest {
    @Test
    fun `owner and other speakers retain different learning authority tags`() {
        val at = Instant.parse("2026-09-20T00:00:00Z")
        val owner = PersonalConversationTurn(
            source = PersonalConversationSource.WHATSAPP,
            conversationId = "thread-1",
            speaker = PersonalConversationSpeaker.OWNER,
            text = "zieh durch",
            observedAt = at,
        ).toPhoton()
        val other = PersonalConversationTurn(
            source = PersonalConversationSource.WHATSAPP,
            conversationId = "thread-1",
            speaker = PersonalConversationSpeaker.OTHER,
            text = "zieh durch",
            observedAt = at,
        ).toPhoton()

        assertTrue("speaker:owner" in owner.tags)
        assertFalse("speaker:owner" in other.tags)
        assertTrue("speaker:other" in other.tags)
        assertTrue("corpus:archive" in owner.tags)
        assertTrue("corpus:archive" in other.tags)
    }
}
