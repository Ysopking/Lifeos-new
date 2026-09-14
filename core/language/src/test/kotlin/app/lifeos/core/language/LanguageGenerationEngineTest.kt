package app.lifeos.core.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LanguageGenerationEngineTest {
    private val understanding = LanguageUnderstandingEngine()
    private val generation = LanguageGenerationEngine(understanding = understanding)

    @Test
    fun `search goal is regenerated and understood as the same intent`() {
        val original = understanding.understand("Suche Bilder aus Berlin")

        val generated = generation.generate(original.goal)
        val reparsed = generated.winner.roundTrip

        assertEquals(IntentType.SEARCH, reparsed.goal.intent)
        assertTrue(generated.winner.intentPreserved)
        assertTrue(generated.winner.semanticPreservation >= 0.65)
        assertTrue(
            reparsed.goal.entities.any {
                it.type == EntityType.LOCATION && it.normalizedValue.equals("berlin", ignoreCase = true)
            }
        )
    }

    @Test
    fun `image creation semantics survive generation understanding loop`() {
        val original = understanding.understand("Erzeuge ein Bild von zwei Leuten, die Fußball spielen.")

        val generated = generation.generate(original.goal)
        val reparsed = generated.winner.roundTrip

        assertEquals(IntentType.CREATE_IMAGE, reparsed.goal.intent)
        assertTrue(generated.winner.intentPreserved)
        assertTrue(reparsed.goal.entities.any { it.type == EntityType.NUMBER && it.normalizedValue == "2" })
        assertTrue(reparsed.linguisticField?.semanticActivation("FOOTBALL") ?: 0.0 > 0.5)
        assertTrue(reparsed.linguisticField?.semanticActivation("ACTION_PLAY") ?: 0.0 > 0.5)
    }

    @Test
    fun `candidate ranking prefers semantic preservation rather than candidate order`() {
        val original = understanding.understand("Erinnere mich morgen um 16:30 an den Termin")

        val generated = generation.generate(original.goal)

        assertEquals(IntentType.SCHEDULE, generated.winner.roundTrip.goal.intent)
        assertTrue(generated.winner.intentPreserved)
        assertTrue(generated.winner.entityCoverage > 0.0)
        assertTrue(
            generated.alternatives.all {
                generated.winner.semanticPreservation >= it.semanticPreservation
            }
        )
    }

    @Test
    fun `generation is deterministic for the same semantic target`() {
        val goal = understanding.understand("Suche Bilder aus Berlin").goal

        val first = generation.generate(goal)
        val second = generation.generate(goal)

        assertEquals(first.text, second.text)
        assertEquals(first.winner.semanticPreservation, second.winner.semanticPreservation)
        assertEquals(first.winner.roundTrip.goal, second.winner.roundTrip.goal)
    }
}
