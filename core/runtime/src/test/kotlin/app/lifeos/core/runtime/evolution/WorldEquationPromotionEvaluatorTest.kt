package app.lifeos.core.runtime.evolution

import app.lifeos.core.field.world.WorldCoefficientId
import app.lifeos.core.field.world.WorldEquationSpec
import app.lifeos.core.runtime.world.CognitiveWorldEquationProfile
import app.lifeos.core.runtime.world.WorldFormulaStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorldEquationPromotionEvaluatorTest {
    private val baseline = CognitiveWorldEquationProfile().spec
    private val candidate = changedCandidate(baseline)
    private val protocol = WorldEquationEvaluationProtocol(
        version = "test-protocol-v1",
        primaryMetric = WorldEquationPrimaryMetric.STABILIZATION_ITERATIONS,
        minimumIndependentRuns = 3,
        minimumDistinctWorkloads = 2,
        minimumActiveObservationsPerChangedCoefficient = 2,
    )
    private val policy = WorldEquationPromotionPolicy(
        version = "test-policy-v1",
        primaryImprovementMargin = 0.0,
        statusNonInferiorityMargin = 0.0,
        minimumImprovedRunFraction = 0.60,
        workloadNonInferiorityMargin = 0.0,
        minimumCoefficientNormRatio = 0.20,
    )
    private val evaluator = WorldEquationPromotionEvaluator(policy)

    @Test
    fun promotableRequiresAllHardGates() {
        val evidence = evidence(
            observations = listOf(
                observation("run-1", "workload-a", 5, 3),
                observation("run-2", "workload-a", 6, 4),
                observation("run-3", "workload-b", 5, 4),
            )
        )

        val verdict = evaluator.evaluate(candidate, baseline, evidence)

        assertEquals(WorldEquationPromotionDecision.PROMOTABLE, verdict.decision)
        assertTrue(verdict.gateResults.all { it.status == WorldEquationGateStatus.PASS })
    }

    @Test
    fun insufficientIndependentRunsCannotPromote() {
        val evidence = evidence(
            observations = listOf(
                observation("run-1", "workload-a", 5, 3),
                observation("run-2", "workload-b", 5, 3),
            )
        )

        val verdict = evaluator.evaluate(candidate, baseline, evidence)

        assertTrue(verdict.decision != WorldEquationPromotionDecision.PROMOTABLE)
        assertTrue(verdict.gateResults.any { it.status == WorldEquationGateStatus.INCONCLUSIVE })
    }

    @Test
    fun regressionRejectsCandidate() {
        val evidence = evidence(
            observations = listOf(
                observation("run-1", "workload-a", 3, 5),
                observation("run-2", "workload-a", 3, 5),
                observation("run-3", "workload-b", 3, 5),
            )
        )

        assertEquals(
            WorldEquationPromotionDecision.REJECTED,
            evaluator.evaluate(candidate, baseline, evidence).decision,
        )
    }

    @Test
    fun policyFingerprintCannotBeSubstituted() {
        val evidence = evidence(
            observations = listOf(
                observation("run-1", "workload-a", 5, 3),
                observation("run-2", "workload-a", 6, 4),
                observation("run-3", "workload-b", 5, 4),
            ),
            policyFingerprint = "wrong-policy",
        )

        assertFailsWith<IllegalArgumentException> {
            evaluator.evaluate(candidate, baseline, evidence)
        }
    }

    private fun evidence(
        observations: List<WorldEquationShadowObservation>,
        policyFingerprint: String = policy.fingerprint(),
    ) = WorldEquationEvidenceSet.empty(
        candidate = candidate,
        baseline = baseline,
        protocol = protocol,
        policyFingerprint = policyFingerprint,
    ).copy(observations = observations)

    private fun observation(
        runId: String,
        workloadId: String,
        baselineIterations: Int,
        candidateIterations: Int,
    ): WorldEquationShadowObservation {
        val changed = candidate.changedCoefficientIdsComparedWith(baseline)
        return WorldEquationShadowObservation(
            caseFingerprint = "case:" + runId,
            runId = runId,
            workloadId = workloadId,
            baselineEquationFingerprint = baseline.fingerprint(),
            candidateEquationFingerprint = candidate.fingerprint(),
            baseline = metrics(baselineIterations, changed),
            candidate = metrics(candidateIterations, changed),
        )
    }

    private fun metrics(
        iterations: Int,
        active: Set<WorldCoefficientId>,
    ) = WorldEquationRunMetrics(
        status = WorldFormulaStatus.CONVERGED,
        iterationCount = iterations,
        conflictCount = 0,
        anomalyCount = 0,
        terminalDelta = 0.0,
        activeCoefficientIds = active,
    )

    private fun changedCandidate(
        baseline: WorldEquationSpec,
    ): WorldEquationSpec {
        val first = baseline.stableCoefficients().first()
        return baseline.copy(
            version = baseline.version + "-candidate",
            coefficients = baseline.coefficients.map {
                if (it.id == first.id) {
                    it.copy(
                        multiplier = if (it.multiplier < 0.9) {
                            it.multiplier + 0.05
                        } else {
                            it.multiplier - 0.05
                        },
                    )
                } else {
                    it
                }
            },
        )
    }
}
