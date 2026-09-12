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

    @Test
    fun `projects autonomous workshop request and evolution handoff without duplicate chat photon`() {
        val sourceId = PhotonId("source-photon")
        val request = Photon(
            id = PhotonId("tool-request"),
            content = """
                LIFEOS_AUTONOMOUS_TOOL_WORKSHOP_REQUEST_V1
                capability=buildstudio.run
            """.trimIndent(),
            provenance = Provenance(
                source = "autonomous-capability-gap-request",
                actor = "lifeos",
                createdAt = Instant.parse("2026-09-12T00:00:02Z"),
                parentIds = setOf(sourceId),
            ),
            tags = setOf("capability-gap", "tool-request", "autonomous-request", "non-activating"),
        )
        val outcome = Photon(
            id = PhotonId("tool-outcome"),
            content = """
                LIFEOS_TOOL_WORKSHOP_OUTCOME_V1
                capability=buildstudio.run
                state=TRIAL_READY
                route=NOVEL_CANARY
                detail=verified candidate ready
            """.trimIndent(),
            provenance = Provenance(
                source = "autonomous-tool-workshop",
                actor = "lifeos",
                createdAt = Instant.parse("2026-09-12T00:00:03Z"),
                parentIds = setOf(request.id),
            ),
            tags = setOf("tool-workshop", "tool-workshop-outcome", "trial_ready", "evolution-handoff", "non-activating"),
        )

        val events = ConversationProjector.project(listOf(request, outcome))

        assertEquals(2, events.size)
        assertEquals(ChatRole.SYSTEM, events[0].role)
        assertEquals(ChatEventType.TOOL_STARTED, events[0].type)
        assertEquals("Autonomer ToolWorkshop gestartet: buildstudio.run wird als fehlende Fähigkeit bearbeitet.", events[0].text)
        assertEquals(ChatRole.SYSTEM, events[1].role)
        assertEquals(ChatEventType.TOOL_RESULT, events[1].type)
        assertEquals("ToolWorkshop: buildstudio.run → TRIAL_READY · Evolution: NOVEL_CANARY · verified candidate ready", events[1].text)
    }
}
