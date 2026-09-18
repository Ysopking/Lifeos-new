package app.lifeos.core.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationFastPathSafetyTest {
    private val guard = FastConversationSafetyGuard()
    private val classifier = ConversationSignalClassifier(guard)

    @Test
    fun `plain acknowledgement remains fast`() {
        val decision = classifier.classify("Okay")

        assertEquals(ConversationPath.FAST_CHAT, decision.path)
        assertTrue(guard.evaluate("Okay").safe)
    }

    @Test
    fun `acknowledgement followed by action is never fast`() {
        val decision = classifier.classify("Okay, sende die Mail.")

        assertFalse(guard.evaluate("Okay, sende die Mail.").safe)
        assertTrue(decision.path != ConversationPath.FAST_CHAT)
    }

    @Test
    fun `courtesy with negated destructive action is never fast`() {
        val safety = guard.evaluate("Danke, aber lösche das bitte nicht.")
        val decision = classifier.classify("Danke, aber lösche das bitte nicht.")

        assertFalse(safety.safe)
        assertTrue("action-verb" in safety.reasons)
        assertTrue("negation" in safety.reasons)
        assertTrue("multi-clause" in safety.reasons)
        assertTrue(decision.path != ConversationPath.FAST_CHAT)
    }

    @Test
    fun `quotation condition and unresolved reference force cognitive path`() {
        listOf(
            "Er sagte: „Sende die Mail.“",
            "Wenn es klappt, okay.",
            "Okay, das andere.",
        ).forEach { text ->
            assertFalse(guard.evaluate(text).safe, text)
            assertTrue(classifier.classify(text).path != ConversationPath.FAST_CHAT, text)
        }
    }
}
