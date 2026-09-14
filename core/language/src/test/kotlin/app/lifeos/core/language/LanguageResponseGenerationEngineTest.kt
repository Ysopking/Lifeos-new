package app.lifeos.core.language

import kotlin.test.Test
import kotlin.test.assertEquals
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
