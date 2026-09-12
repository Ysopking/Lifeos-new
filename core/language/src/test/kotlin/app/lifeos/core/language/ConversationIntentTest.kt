package app.lifeos.core.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationIntentTest {
    private val engine = LanguageUnderstandingEngine()

    @Test
    fun `standalone greeting becomes conversation instead of unknown`() {
        val result = engine.understand("Hallo")

        assertEquals(IntentType.CONVERSATION, result.goal.intent)
        assertTrue(result.intentEvidence.first().score > 0.80)
    }

    @Test
    fun `social check in outranks generic question routing`() {
        val result = engine.understand("Wie geht's dir?")

        assertEquals(IntentType.CONVERSATION, result.goal.intent)
        assertTrue(result.intentEvidence.any { it.intent == IntentType.QUERY })
    }

    @Test
    fun `greeting prefix does not steal a real task`() {
        val result = engine.understand("Hallo, suche aktuelle Meldungen")

        assertEquals(IntentType.SEARCH, result.goal.intent)
    }
}
