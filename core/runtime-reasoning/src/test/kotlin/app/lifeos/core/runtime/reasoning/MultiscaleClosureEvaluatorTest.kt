package app.lifeos.core.runtime.reasoning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MultiscaleClosureEvaluatorTest {
    private val profile = "profile:v2.2"

    @Test
    fun `exact commuting future laws establish exact closure after minimum evidence`() {
        val evaluator = evaluator(maxError = 10_000L)

        val result = evaluator.evaluate(
            profile,
            listOf(
                sample("micro:1", law(700_000L), law(700_000L), "e1"),
                sample("micro:2", law(400_000L), law(400_000L), "e2"),
            ),
        )

        assertEquals(MultiscaleClosureStatus.EXACT, result.status)
        assertEquals(0L, result.maximumObservedErrorMicros)
        assertEquals(true, result.autonomousMacroDynamicsEstablished)
    }

    @Test
    fun `bounded mismatch is approximate rather than exact closure`() {
        val evaluator = evaluator(maxError = 30_000L)

        val result = evaluator.evaluate(
            profile,
            listOf(
                sample("micro:1", law(700_000L), law(680_000L), "e1"),
                sample("micro:2", law(400_000L), law(420_000L), "e2"),
            ),
        )

        assertEquals(MultiscaleClosureStatus.APPROXIMATE, result.status)
        assertEquals(20_000L, result.maximumObservedErrorMicros)
    }

    @Test
    fun `mismatch above frozen tolerance proves non closure for sample`() {
        val evaluator = evaluator(maxError = 30_000L)

        val result = evaluator.evaluate(
            profile,
            listOf(
                sample("micro:1", law(700_000L), law(500_000L), "e1"),
                sample("micro:2", law(400_000L), law(410_000L), "e2"),
            ),
        )

        assertEquals(MultiscaleClosureStatus.NOT_CLOSED, result.status)
        assertEquals(200_000L, result.maximumObservedErrorMicros)
        assertEquals(1, result.failingSampleFingerprints.size)
        assertFalse(result.autonomousMacroDynamicsEstablished)
    }

    @Test
    fun `one matching sample is unresolved rather than proof of closure`() {
        val result = evaluator(maxError = 0L).evaluate(
            profile,
            listOf(sample("micro:1", law(700_000L), law(700_000L), "e1")),
        )

        assertEquals(MultiscaleClosureStatus.UNRESOLVED, result.status)
        assertFalse(result.autonomousMacroDynamicsEstablished)
    }

    @Test
    fun `empty sample set remains unresolved`() {
        val result = evaluator(maxError = 0L).evaluate(profile, emptyList())

        assertEquals(MultiscaleClosureStatus.UNRESOLVED, result.status)
        assertEquals(null, result.maximumObservedErrorMicros)
    }

    @Test
    fun `sample order cannot change multiscale assessment`() {
        val first = sample("micro:1", law(700_000L), law(680_000L), "e1")
        val second = sample("micro:2", law(400_000L), law(420_000L), "e2")
        val evaluator = evaluator(maxError = 30_000L)

        assertEquals(
            evaluator.evaluate(profile, listOf(first, second)),
            evaluator.evaluate(profile, listOf(second, first)),
        )
    }

    @Test
    fun `closure assessment grants no truth authority`() {
        val result = evaluator(maxError = 0L).evaluate(
            profile,
            listOf(
                sample("micro:1", law(700_000L), law(700_000L), "e1"),
                sample("micro:2", law(400_000L), law(400_000L), "e2"),
            ),
        )

        assertFalse(result.truthAuthority)
    }

    private fun evaluator(
        maxError: Long,
    ) = MultiscaleClosureEvaluator(
        MultiscaleClosurePolicy(
            aggregationRuleFingerprint = "aggregation:owner-relevant-state/v1",
            maximumApproximationErrorMicros = maxError,
            minimumComparableSamples = 2,
        )
    )

    private fun sample(
        micro: String,
        projectedMicroLaw: DiscreteFutureLaw,
        macroLaw: DiscreteFutureLaw,
        evidence: String,
    ) = MultiscaleTransitionSample.create(
        realizationProfileFingerprint = profile,
        microStateFingerprint = micro,
        projectedMacroStateFingerprint = "macro:$micro",
        projectedMicroSuccessorLaw = projectedMicroLaw,
        macroSuccessorLaw = macroLaw,
        evidenceFingerprint = evidence,
    )

    private fun law(
        firstOutcomeMicros: Long,
    ): DiscreteFutureLaw = DiscreteFutureLaw.create(
        if (firstOutcomeMicros == PREDICTIVE_PROBABILITY_SCALE) {
            mapOf("a" to firstOutcomeMicros)
        } else {
            mapOf(
                "a" to firstOutcomeMicros,
                "b" to PREDICTIVE_PROBABILITY_SCALE - firstOutcomeMicros,
            )
        }
    )
}
