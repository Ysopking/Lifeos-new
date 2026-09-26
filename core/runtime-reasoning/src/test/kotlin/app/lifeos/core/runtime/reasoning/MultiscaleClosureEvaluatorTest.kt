package app.lifeos.core.runtime.reasoning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MultiscaleClosureEvaluatorTest {
    private val policy = MultiscaleClosurePolicy(
        aggregationRuleFingerprint = "aggregation:v1",
        maximumApproximationErrorMicros = 50_000L,
    )
    private val evaluator = MultiscaleClosureEvaluator(policy)

    @Test
    fun `identical projected and macro laws are exact`() {
        val law = law("a" to 600_000L, "b" to 400_000L)
        val result = evaluator.evaluate(listOf(sample(law, law, "e1")))

        assertEquals(MultiscaleClosureStatus.EXACT, result.status)
        assertEquals(0L, result.maximumObservedErrorMicros)
    }

    @Test
    fun `bounded mismatch is explicitly approximate`() {
        val result = evaluator.evaluate(
            listOf(
                sample(
                    law("a" to 600_000L, "b" to 400_000L),
                    law("a" to 570_000L, "b" to 430_000L),
                    "e1",
                )
            )
        )

        assertEquals(MultiscaleClosureStatus.APPROXIMATE, result.status)
        assertEquals(30_000L, result.maximumObservedErrorMicros)
    }

    @Test
    fun `mismatch beyond frozen tolerance is not closed`() {
        val result = evaluator.evaluate(
            listOf(
                sample(
                    law("a" to 800_000L, "b" to 200_000L),
                    law("a" to 500_000L, "b" to 500_000L),
                    "e1",
                )
            )
        )

        assertEquals(MultiscaleClosureStatus.NOT_CLOSED, result.status)
        assertEquals(300_000L, result.maximumObservedErrorMicros)
    }

    @Test
    fun `no samples remain unresolved`() {
        val result = evaluator.evaluate(emptyList())

        assertEquals(MultiscaleClosureStatus.UNRESOLVED, result.status)
        assertEquals(null, result.maximumObservedErrorMicros)
    }

    @Test
    fun `worst observed transition determines closure status`() {
        val result = evaluator.evaluate(
            listOf(
                sample(
                    law("a" to 600_000L, "b" to 400_000L),
                    law("a" to 590_000L, "b" to 410_000L),
                    "e1",
                ),
                sample(
                    law("a" to 900_000L, "b" to 100_000L),
                    law("a" to 500_000L, "b" to 500_000L),
                    "e2",
                ),
            )
        )

        assertEquals(MultiscaleClosureStatus.NOT_CLOSED, result.status)
        assertEquals(400_000L, result.maximumObservedErrorMicros)
    }

    @Test
    fun `result is non-authoritative`() {
        val law = law("a" to PREDICTIVE_PROBABILITY_SCALE)
        val result = evaluator.evaluate(listOf(sample(law, law, "e1")))

        assertFalse(result.truthAuthority)
    }

    private fun sample(
        projected: DiscreteFutureLaw,
        macro: DiscreteFutureLaw,
        evidence: String,
    ) = MultiscaleTransitionSample(
        realizationProfileFingerprint = "profile:v2.2",
        microStateFingerprint = "micro:$evidence",
        projectedMacroStateFingerprint = "macro:$evidence",
        projectedMicroSuccessorLaw = projected,
        macroSuccessorLaw = macro,
        evidenceFingerprint = evidence,
    )

    private fun law(vararg values: Pair<String, Long>) =
        DiscreteFutureLaw.create(linkedMapOf(*values))
}
