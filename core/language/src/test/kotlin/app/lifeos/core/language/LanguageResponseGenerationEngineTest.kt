package app.lifeos.core.language

import app.lifeos.core.model.PhotonId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanguageResponseGenerationEngineTest {
    private val generation = LanguageResponseGenerationEngine()

    @Test
    fun `evidence response preserves factual and semantic content`() {
        val target = LanguageResponseTarget(
            act = LanguageResponseAct.EVIDENCE,
            language = LanguageCode.DE,
            facts = listOf(
                LanguageResponseFact(
                    statement = "In Berlin spielen Leute Fußball",
                    semanticTags = setOf("LOCATION_BERLIN", "PERSON", "FOOTBALL", "ACTION_PLAY"),
                    confidence = 0.88,
                )
            ),
        )

        val result = generation.generate(target)

        assertTrue(result.winner.factCoverage >= 0.99)
        assertTrue(result.winner.semanticCoverage > 0.50)
        assertTrue(result.winner.actCuePreserved)
        assertTrue(result.text.contains("Berlin", ignoreCase = true))
        assertTrue(result.text.contains("Fußball", ignoreCase = true))
    }

    @Test
    fun `uncertainty remains explicit while evidence statement is preserved`() {
        val fact = "Berlin ist der wahrscheinlichste Ort"
        val target = LanguageResponseTarget(
            act = LanguageResponseAct.UNCERTAINTY,
            language = LanguageCode.DE,
            facts = listOf(LanguageResponseFact(fact, semanticTags = setOf("LOCATION_BERLIN"), confidence = 0.61)),
        )

        val result = generation.generate(target)

        assertTrue(result.winner.actCuePreserved)
        assertTrue(result.winner.factCoverage >= 0.99)
        assertTrue(result.text.contains("Berlin", ignoreCase = true))
        assertTrue(
            listOf("nicht eindeutig", "unsicher", "vorläufig").any { cue ->
                result.text.contains(cue, ignoreCase = true)
            }
        )
    }

    @Test
    fun `multiple evidence facts survive response generation`() {
        val target = LanguageResponseTarget(
            act = LanguageResponseAct.EVIDENCE,
            language = LanguageCode.DE,
            facts = listOf(
                LanguageResponseFact("Quelle A nennt Berlin", setOf("LOCATION_BERLIN"), 0.91),
                LanguageResponseFact("Quelle B nennt Fußball", setOf("FOOTBALL"), 0.84),
            ),
        )

        val result = generation.generate(target)

        assertTrue(result.winner.factCoverage >= 0.99)
        assertTrue(result.text.contains("Quelle A"))
        assertTrue(result.text.contains("Quelle B"))
    }

    @Test
    fun `response round trip resolves references against the supplied language context`() {
        val imageId = PhotonId("image-context")
        val context = LanguageContext(
            items = listOf(
                LanguageContextItem(
                    photonId = imageId,
                    kind = "image",
                    tags = setOf("image"),
                    createdAt = Instant.parse("2026-09-14T14:00:00Z"),
                    active = true,
                    contentTerms = setOf("bild"),
                )
            ),
            now = Instant.parse("2026-09-14T14:01:00Z"),
        )
        val target = LanguageResponseTarget(
            act = LanguageResponseAct.ASSERT,
            language = LanguageCode.DE,
            facts = listOf(
                LanguageResponseFact(
                    statement = "Mach dieses Bild heller",
                    semanticTags = setOf("IMAGE"),
                    confidence = 0.95,
                )
            ),
        )

        val result = generation.generate(target, context)

        assertEquals(context, result.winner.roundTrip.context)
        assertTrue(result.winner.roundTrip.goal.references.any { it.targetPhotonId == imageId })
    }

    @Test
    fun `german greeting becomes a natural reply without internal conversation metadata`() {
        val target = LanguageResponseTarget(
            act = LanguageResponseAct.ASSERT,
            language = LanguageCode.DE,
            facts = listOf(
                LanguageResponseFact(
                    statement = "Der Gesprächskontext ist aktiv und dein Anliegen wurde semantisch erfasst: Hallo",
                    semanticTags = setOf("CONVERSATION"),
                    confidence = 0.95,
                )
            ),
        )

        val result = generation.generate(target)

        assertTrue(result.text in setOf("Hallo!", "Hi!", "Hey!"))
        assertFalse(result.text.contains("Gesprächskontext", ignoreCase = true))
        assertFalse(result.text.contains("semantisch erfasst", ignoreCase = true))
    }

    @Test
    fun `english thanks becomes a courtesy reply without internal conversation metadata`() {
        val target = LanguageResponseTarget(
            act = LanguageResponseAct.ASSERT,
            language = LanguageCode.EN,
            facts = listOf(
                LanguageResponseFact(
                    statement = "The conversation context is active and your request was captured semantically: Thank you",
                    semanticTags = setOf("CONVERSATION"),
                    confidence = 0.95,
                )
            ),
        )

        val result = generation.generate(target)

        assertTrue(result.text in setOf("You're welcome!", "Gladly!", "Of course!"))
        assertFalse(result.text.contains("conversation context", ignoreCase = true))
        assertFalse(result.text.contains("captured semantically", ignoreCase = true))
    }

    @Test
    fun `german check in produces a bounded ready response without metadata leakage`() {
        val target = LanguageResponseTarget(
            act = LanguageResponseAct.ASSERT,
            language = LanguageCode.DE,
            facts = listOf(
                LanguageResponseFact(
                    statement = "Der Gesprächskontext ist aktiv und dein Anliegen wurde semantisch erfasst: Wie geht es dir?",
                    semanticTags = setOf("CONVERSATION"),
                    confidence = 0.95,
                )
            ),
        )

        val result = generation.generate(target)

        assertTrue(result.text.contains("bereit", ignoreCase = true))
        assertFalse(result.text.contains("Gesprächskontext", ignoreCase = true))
        assertFalse(result.text.contains("semantisch erfasst", ignoreCase = true))
    }

    @Test
    fun `general conversation remains natural and bounded`() {
        val target = LanguageResponseTarget(
            act = LanguageResponseAct.ASSERT,
            language = LanguageCode.DE,
            facts = listOf(
                LanguageResponseFact(
                    statement = "Der Gesprächskontext ist aktiv und dein Anliegen wurde semantisch erfasst: Ich wollte dir nur kurz etwas erzählen",
                    semanticTags = setOf("CONVERSATION"),
                    confidence = 0.95,
                )
            ),
        )

        val result = generation.generate(target)

        assertTrue(result.text.length <= 120)
        assertFalse(result.text.contains("Gesprächskontext", ignoreCase = true))
        assertFalse(result.text.contains("semantisch erfasst", ignoreCase = true))
        assertFalse(result.text.contains("intent:", ignoreCase = true))
    }

    @Test
    fun `response generation is deterministic`() {
        val target = LanguageResponseTarget(
            act = LanguageResponseAct.REPORT_SUCCESS,
            language = LanguageCode.DE,
            facts = listOf(LanguageResponseFact("Die Recherche wurde abgeschlossen", confidence = 1.0)),
        )

        val first = generation.generate(target)
        val second = generation.generate(target)

        assertEquals(first.text, second.text)
        assertEquals(first.winner.semanticPreservation, second.winner.semanticPreservation)
        assertEquals(first.winner.roundTrip.goal, second.winner.roundTrip.goal)
    }
}
