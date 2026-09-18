package app.lifeos.core.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SemanticInterpretationQualityTest {
    private val engine = LanguageUnderstandingEngine()

    @Test
    fun highConfidenceQuestionHasZeroExecutionReadiness() {
        val result = engine.understand("Wie erstelle ich ein Bild?")

        assertEquals(IntentType.QUERY, result.goal.intent)
        assertTrue(result.intentEvidence.any { it.intent == IntentType.CREATE_IMAGE })
        assertEquals(0.0, result.goal.interpretationQuality.executionReadiness)
        assertTrue(result.goal.interpretationQuality.evidenceStrength > 0.0)
    }

    @Test
    fun completeCommandCarriesExecutionReadiness() {
        val result = engine.understand("Erstelle ein Bild.")

        assertTrue(result.goal.interpretationQuality.executionReadiness >=
            SemanticActionNode.MIN_EXECUTION_READINESS)
    }

    @Test
    fun negationZeroesExecutionWithoutErasingTopicEvidence() {
        val result = engine.understand("Sende diese Mail nicht.")

        assertEquals(0.0, result.goal.interpretationQuality.executionReadiness)
        assertTrue(result.goal.interpretationQuality.evidenceStrength > 0.0)
        assertTrue(result.goal.interpretationQuality.contradictionCount >= 1)
    }

    @Test
    fun sameInputProducesSameQualityVector() {
        val first = engine.understand("Erstelle ein Bild.")
        val second = engine.understand("Erstelle ein Bild.")

        assertEquals(first.goal.interpretationQuality, second.goal.interpretationQuality)
    }
}
