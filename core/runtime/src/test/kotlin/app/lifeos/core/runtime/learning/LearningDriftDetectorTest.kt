package app.lifeos.core.runtime.learning

import app.lifeos.core.field.HypothesisId
import app.lifeos.core.runtime.convergence.ConvergenceConfidenceBand
import app.lifeos.core.runtime.convergence.ConvergenceDecisionId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LearningDriftDetectorTest {
    private val at = Instant.parse("2026-09-11T11:00:00Z")

    @Test
    fun `insufficient verified samples never trigger rollback`() {
        val adaptation = adaptation("seed", 1.0)
        val baseline = listOf(score("b1", 0.9), score("b2", 0.9))
        val adapted = listOf(score("a1", 0.1), score("a2", 0.1))

        assertNull(LearningDriftDetector().evaluate(adaptation, baseline, adapted))
    }

    @Test
    fun `material verified quality regression emits deterministic rollback proposal`() {
        val adaptation = adaptation("seed", 1.0)
        val baseline = listOf(score("b1", 0.9), score("b2", 0.9), score("b3", 0.9))
        val adapted = listOf(score("a1", 0.1), score("a2", 0.2), score("a3", 0.1))
        val detector = LearningDriftDetector()

        val first = requireNotNull(detector.evaluate(adaptation, baseline, adapted))
        val second = requireNotNull(detector.evaluate(adaptation, baseline.reversed(), adapted.reversed()))

        assertEquals(first, second)
        assertEquals(adaptation.id, first.adaptationId)
        assertEquals(adaptation.target, first.target)
        assertTrue(first.baselineMeanQuality > first.adaptedMeanQuality)
        assertTrue(first.id.startsWith("learning-rollback-proposal:"))
    }

    @Test
    fun `small quality change does not trigger rollback`() {
        val adaptation = adaptation("seed", 1.0)
        val baseline = listOf(score("b1", 0.8), score("b2", 0.8), score("b3", 0.8))
        val adapted = listOf(score("a1", 0.75), score("a2", 0.74), score("a3", 0.76))

        assertNull(LearningDriftDetector().evaluate(adaptation, baseline, adapted))
    }

    private fun adaptation(action: String, value: Double): LearningAdaptation {
        val prediction = prediction(action)
        val score = verifiedScore(prediction, value, "adaptation-$action")
        return requireNotNull(
            LearningAdaptationPlanner().plan(
                target = LearningAdaptationTarget(
                    LearningAdaptationTargetKind.PROVIDER_RELIABILITY,
                    "provider-a",
                ),
                baselineValue = 0.6,
                currentEffectiveValue = 0.6,
                score = score,
                createdAt = at.plusSeconds(2),
            )
        )
    }

    private fun score(id: String, value: Double): OutcomeScore {
        val prediction = prediction(id, at.plusSeconds(id.hashCode().toLong().let { kotlin.math.abs(it % 1000) } + 10))
        return verifiedScore(prediction, value, "verifier-$id")
    }

    private fun prediction(action: String, createdAt: Instant = at) = OutcomePrediction.create(
        actionId = "action-$action",
        decisionId = ConvergenceDecisionId("decision-$action"),
        expectedHypotheses = listOf(
            OutcomeExpectedHypothesis(
                hypothesisId = HypothesisId("hypothesis-$action"),
                confidenceBand = ConvergenceConfidenceBand(0.70, 0.80, 0.90),
            )
        ),
        providerIds = listOf("provider-a"),
        fieldSnapshotFingerprints = listOf("field-$action"),
        thoughtGraphWorkingSetFingerprint = "working-set-$action",
        decisionPolicyFingerprint = "policy-v1",
        createdAt = createdAt,
    )

    private fun verifiedScore(
        prediction: OutcomePrediction,
        value: Double,
        source: String,
    ): OutcomeScore = OutcomeScorer().score(
        prediction,
        listOf(
            OutcomeEvidence.create(
                predictionId = prediction.id,
                sourceClass = OutcomeEvidenceSourceClass.EXTERNAL_VERIFICATION,
                sourceId = source,
                sourceFingerprint = "fingerprint-$source",
                signal = OutcomeSignal(
                    completion = value,
                    correctness = value,
                    usefulness = value,
                    policyCompliance = value,
                ),
                confidence = 1.0,
                independentOfProviderIds = setOf("provider-a"),
                observedAt = prediction.createdAt.plusSeconds(1),
                reason = "verified-$source",
            )
        ),
    )
}
