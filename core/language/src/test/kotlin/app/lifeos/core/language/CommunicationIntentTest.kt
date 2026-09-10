package app.lifeos.core.language

import app.lifeos.core.model.PhotonId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommunicationIntentTest {
    private val engine = LanguageUnderstandingEngine()

    @Test
    fun `teile dieses ergebnis resolves communicate intent and result reference`() {
        val resultId = PhotonId("answer-result")
        val now = Instant.parse("2026-09-10T20:00:00Z")
        val context = LanguageContext(
            now = now,
            items = listOf(
                LanguageContextItem(
                    photonId = resultId,
                    kind = "result",
                    tags = setOf("answer", "result"),
                    createdAt = now.minusSeconds(30),
                    active = false,
                    contentTerms = setOf("antwort"),
                )
            ),
        )

        val understood = engine.understand("Teile dieses Ergebnis", context)

        assertEquals(IntentType.COMMUNICATE, understood.goal.intent)
        assertTrue(understood.goal.references.any {
            it.targetPhotonId == resultId && it.score >= 0.55 && "result" in it.expression.preferredKinds
        })
    }

    @Test
    fun `share last result is communicate in english`() {
        val understood = engine.understand("Share the last result")
        assertEquals(IntentType.COMMUNICATE, understood.goal.intent)
        assertTrue(understood.goal.references.any { it.expression.kind == ReferenceKind.LAST_RESULT })
    }
}
