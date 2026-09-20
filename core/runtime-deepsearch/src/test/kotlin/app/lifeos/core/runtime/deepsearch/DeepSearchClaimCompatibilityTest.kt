package app.lifeos.core.runtime.deepsearch

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeepSearchClaimCompatibilityTest {
    private val detector = DeepSearchClaimCompatibility()

    @Test
    fun `same topic with incompatible single number is contradiction`() {
        val result = detector.evaluate(
            "Die Widerspruchsfrist beträgt 1 Monat.",
            "Die Widerspruchsfrist beträgt 2 Monate.",
        )
        assertTrue(result.comparable)
        assertTrue(result.contradiction)
    }

    @Test
    fun `different topics are not forced into contradiction`() {
        val result = detector.evaluate(
            "Die Widerspruchsfrist beträgt 1 Monat.",
            "Die Telefonnummer der Behörde lautet 12345.",
        )
        assertFalse(result.contradiction)
    }

    @Test
    fun `matching claim remains compatible`() {
        val result = detector.evaluate(
            "Die Widerspruchsfrist beträgt 1 Monat.",
            "Für die Widerspruchsfrist gilt 1 Monat.",
        )
        assertTrue(result.comparable)
        assertFalse(result.contradiction)
    }
}
