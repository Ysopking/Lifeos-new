package app.lifeos.core.runtime.evolution

import app.lifeos.core.field.world.WorldCoefficientId
import app.lifeos.core.runtime.world.CognitiveWorldEquationProfile
import app.lifeos.core.runtime.world.WorldFormulaStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class WorldEquationEvidenceCoordinatorTest {
    @Test
    fun pairedEvidencePromotesOnlyAfterProtocolGatesPass() = runTest {
        val baseline = CognitiveWorldEquationProfile().spec
        val coefficient = baseline.stableCoefficients().first()
        val candidate = baseline.copy(
            version = baseline.version + "-candidate",
            coefficients = baseline.coefficients.map {
                if (it.id == coefficient.id) {
                    it.copy(
                        multiplier = if (it.multiplier < 0.9) {
                            it.multiplier + 0.05
                        } else {
                            it.multiplier - 0.05
                        }
                    )
                } else it
            },
        )
        val protocol = WorldEquationEvaluationProtocol(
            version = "lifecycle-test-v1",
            primaryMetric = WorldEquationPrimaryMetric.STABILIZATION_ITERATIONS,
            minimumIndependentRuns = 3,
            minimumDistinctWorkloads = 2,
            minimumActiveObservationsPerChangedCoefficient = 2,
        )
        val policy = WorldEquationPromotionPolicy(
            version = "lifecycle-policy-v1",
            primaryImprovementMargin = 0.0,
            statusNonInferiorityMargin = 0.0,
            minimumImprovedRunFraction = 0.60,
            workloadNonInferiorityMargin = 0.0,
            minimumCoefficientNormRatio = 0.20,
        )
        val repository = InMemoryWorldEquationEvidenceRepository()
        val coordinator = WorldEquationEvidenceCoordinator(
            repository,
            WorldEquationPromotionEvaluator(policy),
        )

        val shadow = coordinator.beginShadow(candidate, baseline, protocol)
        assertEquals(WorldEquationLifecycleState.SHADOW, shadow.state)

        coordinator.recordObservation(
            candidate,
            baseline,
            observation(baseline, candidate, coefficient.id, "run-1", "a"),
        )
        coordinator.recordObservation(
            candidate,
            baseline,
            observation(baseline, candidate, coefficient.id, "run-2", "a"),
        )
        val promotable = coordinator.recordObservation(
            candidate,
            baseline,
            observation(
                baseline,
                candidate,
                coefficient.id,
                "run-3",
                "b",
                WorldEquationEvidencePartition.HOLDOUT,
            ),
        )

        assertEquals(WorldEquationLifecycleState.PROMOTABLE, promotable.state)
    }

    private fun observation(
        baseline: app.lifeos.core.field.world.WorldEquationSpec,
        candidate: app.lifeos.core.field.world.WorldEquationSpec,
        coefficientId: WorldCoefficientId,
        runId: String,
        workloadId: String,
        partition: WorldEquationEvidencePartition = WorldEquationEvidencePartition.SHADOW,
    ) = WorldEquationShadowObservation(
        caseFingerprint = "case:" + runId,
        runId = runId,
        workloadId = workloadId,
        partition = partition,
        baselineEquationFingerprint = baseline.fingerprint(),
        candidateEquationFingerprint = candidate.fingerprint(),
        baseline = WorldEquationRunMetrics(
            status = WorldFormulaStatus.CONVERGED,
            iterationCount = 5,
            conflictCount = 0,
            anomalyCount = 0,
            terminalDelta = 0.0,
            activeCoefficientIds = setOf(coefficientId),
        ),
        candidate = WorldEquationRunMetrics(
            status = WorldFormulaStatus.CONVERGED,
            iterationCount = 3,
            conflictCount = 0,
            anomalyCount = 0,
            terminalDelta = 0.0,
            activeCoefficientIds = setOf(coefficientId),
        ),
    )
}
