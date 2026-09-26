package app.lifeos.next.ui.speech

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidSpeechOutputTest {
    @Test
    fun shortSpeechRemainsOneChunk() {
        assertEquals(
            listOf("Eine klare kurze Antwort."),
            AndroidSpeechOutput.speechChunks(
                text = "Eine klare kurze Antwort.",
                maxInputLength = 256,
            ),
        )
    }

    @Test
    fun longSpeechSplitsOnReadableBoundariesWithinTtsLimit() {
        val text = buildString {
            repeat(40) { index ->
                append("Satz ").append(index).append(" erklärt den nächsten sinnvollen Schritt. ")
            }
        }

        val chunks = AndroidSpeechOutput.speechChunks(text, maxInputLength = 180)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 148 })
        assertEquals(
            text.trim().replace(Regex("\\s+"), " "),
            chunks.joinToString(" ").replace(Regex("\\s+"), " "),
        )
    }
}
