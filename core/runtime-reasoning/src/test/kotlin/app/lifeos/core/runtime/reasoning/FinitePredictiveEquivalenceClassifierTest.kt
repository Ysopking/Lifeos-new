package app.lifeos.core.runtime.reasoning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class FinitePredictiveEquivalenceClassifierTest {
    private val classifier = FinitePredictiveEquivalenceClassifier(
        PredictiveEquivalencePolicy(
            minimumSamplesPerHistory = 20L,
            mergeMaximumDistanceMicros = 50_000L,
            splitMinimumDistanceMicros = 150_000L,
        )
    )

    @Test
    fun `insufficient data stays unresolved even when observed proportions match exactly`() {
        val result = classifier.classify(
            history("h1", "a" to 5L, "b" to 5L),
            history("h2", "a" to 5L, "b" to 5L),
        )

        assertEquals(PredictiveEquivalenceDecision.UNRESOLVED, result.decision)
        assertNull(result.totalVariationMicros)
    }

    @Test
    fun `sufficient close empirical laws classify merge`() {
        val result = classifier.classify(
            history("h1", "a" to 52L, "b" to 48L),
            history("h2", "a" to 50L, "b" to 50L),
        )

        assertEquals(PredictiveEquivalenceDecision.MERGE, result.decision)
        assertEquals(20_000L, result.totalVariationMicros)
    }

    @Test
    fun `sufficient distant empirical laws classify split`() {
        val result = classifier.classify(
            history("h1", "a" to 90L, "b" to 10L),
            history("h2", "a" to 50L, "b" to 50L),
        )

        assertEquals(PredictiveEquivalenceDecision.SPLIT, result.decision)
        assertEquals(400_000L, result.totalVariationMicros)
    }

    @Test
    fun `intermediate distance stays unresolved instead of being coerced to merge`() {
        val result = classifier.classify(
            history("h1", "a" to 60L, "b" to 40L),
            history("h2", "a" to 50L, "b" to 50L),
        )

        assertEquals(PredictiveEquivalenceDecision.UNRESOLVED, result.decision)
        assertEquals(100_000L, result.totalVariationMicros)
    }

    @Test
    fun `distance accounts for outcomes missing from one empirical support`() {
        val result = classifier.classify(
            history("h1", "a" to 100L),
            history("h2", "b" to 100L),
        )

        assertEquals(PredictiveEquivalenceDecision.SPLIT, result.decision)
        assertEquals(PREDICTIVE_PROBABILITY_SCALE, result.totalVariationMicros)
    }

    @Test
    fun `comparison is deterministic when history argument order changes`() {
        val first = history("h1", "a" to 60L, "b" to 40L)
        val second = history("h2", "a" to 50L, "b" to 50L)

        val forward = classifier.classify(first, second)
        val reverse = classifier.classify(second, first)

        assertEquals(forward, reverse)
    }

    @Test
    fun `cross profile comparison is rejected`() {
        val first = history("h1", "a" to 20L).copy(
            realizationProfileFingerprint = "profile:a"
        )
        val second = history("h2", "a" to 20L).copy(
            realizationProfileFingerprint = "profile:b"
        )

        assertFailsWith<IllegalArgumentException> {
            classifier.classify(first, second)
        }
    }

    @Test
    fun `same history cannot be counted as two independent samples`() {
        val first = history("h1", "a" to 20L)
        val second = history("h1", "a" to 20L)

        assertFailsWith<IllegalArgumentException> {
            classifier.classify(first, second)
        }
    }

    @Test
    fun `assessment remains non authoritative`() {
        val result = classifier.classify(
            history("h1", "a" to 20L),
            history("h2", "a" to 20L),
        )

        assertFalse(result.truthAuthority)
        assertFalse(result.executionAuthority)
    }

    @Test
    fun `policy requires unresolved interval between merge and split thresholds`() {
        assertFailsWith<IllegalArgumentException> {
            PredictiveEquivalencePolicy(
                mergeMaximumDistanceMicros = 100_000L,
                splitMinimumDistanceMicros = 100_000L,
            )
        }
    }

    private fun history(
        id: String,
        vararg counts: Pair<String, Long>,
    ) = EmpiricalPredictiveHistory(
        realizationProfileFingerprint = "profile:v2.2",
        historyFingerprint = id,
        observations = EmpiricalFutureCounts.create(linkedMapOf(*counts)),
    )
}
