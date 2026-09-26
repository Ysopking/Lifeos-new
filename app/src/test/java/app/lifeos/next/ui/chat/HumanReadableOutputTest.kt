package app.lifeos.next.ui.chat

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HumanReadableOutputTest {
    @Test
    fun displayRemovesTechnicalPresentationNoiseWithoutChangingMeaning() {
        val text = HumanReadableOutput.forDisplay(
            "## Ergebnis\n- LIFEOS-Photon gespeichert\n- produktiver Provider fehlt"
        )

        assertFalse(text.contains("##"))
        assertFalse(text.contains("LIFEOS-Photon"))
        assertFalse(text.contains("produktiver Provider"))
        assertTrue(text.contains("lokaler Eintrag"))
        assertTrue(text.contains("benötigte Funktion"))
    }

    @Test
    fun speechRemovesLinksAndCodeFences() {
        val fence = "\u0060\u0060\u0060"
        val spoken = HumanReadableOutput.forSpeech(
            "Siehe [Dokument](https://example.com).\n" +
                fence + "kotlin\nval x = 1\n" + fence
        )

        assertTrue(spoken.contains("Dokument"))
        assertFalse(spoken.contains("https://"))
        assertFalse(spoken.contains(fence))
        assertTrue(spoken.contains("Code ist in der Textansicht sichtbar"))
    }
}
