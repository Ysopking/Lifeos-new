package app.lifeos.core.runtime.chat

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ConversationProjectorTest {
    @Test
    fun `projects persisted turn in chronological chat order`() {
        val user = Photon(
            id = PhotonId("user-photon"),
            content = "Hallo LIFEOS",
            provenance = Provenance(
                source = "lifeos-chat",
                actor = "user",
                createdAt = Instant.parse("2026-09-12T00:00:00Z"),
            ),
            tags = setOf("chat", "chat:user", "conversation:default", "turn:turn-1"),
        )
        val assistant = Photon(
            id = PhotonId("assistant-photon"),
            content = "Hallo",
            provenance = Provenance(
                source = "lifeos-chat",
                actor = "lifeos",
                createdAt = Instant.parse("2026-09-12T00:00:01Z"),
            ),
            tags = setOf("chat", "chat:assistant", "conversation:default", "turn:turn-1"),
        )

        val events = ConversationProjector.project(listOf(assistant, user))

        assertEquals(2, events.size)
        assertEquals(ChatRole.USER, events[0].role)
        assertEquals(ChatRole.LIFEOS, events[1].role)
        assertEquals("turn-1", events[0].turnId)
        assertEquals("turn-1", events[1].turnId)
    }
}
