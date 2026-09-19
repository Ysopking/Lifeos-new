package app.lifeos.core.runtime.evolution

import app.lifeos.core.field.world.WorldEquationSpec
import app.lifeos.core.field.world.WorldFieldVector
import app.lifeos.core.field.world.WorldNodeKind
import app.lifeos.core.field.world.WorldTargetRef
import app.lifeos.core.runtime.world.CognitiveWorldEquationProfile
import app.lifeos.core.runtime.world.InMemoryWorldEquationRegistry
import app.lifeos.core.runtime.world.InMemoryWorldEquationSpecRepository
import app.lifeos.core.runtime.world.WorldEquationActivationAuthority
import app.lifeos.core.runtime.world.WorldEquationHead
import app.lifeos.core.runtime.world.WorldEquationHeadLoadReport
import app.lifeos.core.runtime.world.WorldEquationHeadRepository
import app.lifeos.core.runtime.world.WorldFormulaInputSnapshot
import app.lifeos.core.runtime.world.WorldFormulaRequest
import app.lifeos.core.runtime.world.WorldFormulaStatus
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

class WorldEquationAutoEvolutionCoordinatorTest {
    @Test
    fun hardGatesAutomaticallyPromoteCandidateAndBindActiveEvidence() = runTest {
        val baseline = CognitiveWorldEquationProfile().spec
        val candidate = changedCandidate(baseline)
        val evidenceRepository = InMemoryWorldEquationEvidenceRepository()
        val policy = WorldEquationPromotionPolicy(
            version = "auto-test-policy-v1",
            primaryImprovementMargin = 0.0,
            statusNonInferiorityMargin = 0.0,
            minimumImprovedRunFraction = 0.60,
            workloadNonInferiorityMargin = 0.0,
            minimumCoefficientNormRatio = 0.20,
        )
        val evaluator = WorldEquationPromotionEvaluator(policy)
        val evidenceCoordinator = WorldEquationEvidenceCoordinator(
            evidenceRepository,
            evaluator,
        )
        val gate = WorldEquationEvolutionAdmissionGate(
            evidenceRepository,
            evaluator,
        )
        val headRepository = MemoryHeadRepository()
        val authority = WorldEquationActivationAuthority(
            equations = InMemoryWorldEquationRegistry(listOf(baseline)),
            heads = headRepository,
            baseline = baseline,
            specs = InMemoryWorldEquationSpecRepository(),
            admissionVerifier = gate,
        )
        val activeCoefficient = baseline.stableCoefficients().first().id
        val shadowRunner = WorldEquationShadowRunner { base, cand, case ->
            WorldEquationShadowObservation(
                caseFingerprint = case.fingerprint(),
                runId = case.runId,
                workloadId = case.workloadId,
                baselineEquationFingerprint = base.fingerprint(),
                candidateEquationFingerprint = cand.fingerprint(),
                baseline = metrics(5, activeCoefficient),
                candidate = metrics(3, activeCoefficient),
            )
        }
        val coordinator = WorldEquationAutoEvolutionCoordinator(
            evidence = evidenceRepository,
            evidenceCoordinator = evidenceCoordinator,
            shadow = shadowRunner,
            admissionGate = gate,
            authority = authority,
        )
        val protocol = WorldEquationEvaluationProtocol(
            version = "auto-test-protocol-v1",
            primaryMetric = WorldEquationPrimaryMetric.STABILIZATION_ITERATIONS,
            minimumIndependentRuns = 2,
            minimumDistinctWorkloads = 1,
            minimumActiveObservationsPerChangedCoefficient = 1,
        )

        assertIs<WorldEquationAutoEvolutionResult.Started>(
            coordinator.start(candidate, protocol)
        )
        assertIs<WorldEquationAutoEvolutionResult.Observed>(
            coordinator.observe(candidate, shadowCase("run-1"))
        )
        val promoted = assertIs<WorldEquationAutoEvolutionResult.Promoted>(
            coordinator.observe(candidate, shadowCase("run-2"))
        )

        assertEquals(candidate.version, promoted.head.activeEquationVersion)
        assertEquals(WorldEquationLifecycleState.ACTIVE, promoted.record.state)
        assertEquals(promoted.head.fingerprint, promoted.record.activationHeadFingerprint)
        assertEquals(candidate.version, authority.activeVersion())
    }

    private fun shadowCase(runId: String) = WorldEquationShadowCase(
        runId = runId,
        workloadId = "auto-test-workload",
        request = WorldFormulaRequest(
            inputs = listOf(
                WorldFormulaInputSnapshot(
                    target = WorldTargetRef(WorldNodeKind.GOAL, "auto-test"),
                    vector = WorldFieldVector.EMPTY,
                    sourceSnapshotFingerprint = "auto-test-input",
                )
            ),
            interactions = emptyList(),
            equationVersion = "placeholder",
            observedAt = Instant.parse("2026-09-19T07:20:00Z"),
        ),
    )

    private fun metrics(
        iterations: Int,
        activeCoefficient: app.lifeos.core.field.world.WorldCoefficientId,
    ) = WorldEquationRunMetrics(
        status = WorldFormulaStatus.CONVERGED,
        iterationCount = iterations,
        conflictCount = 0,
        anomalyCount = 0,
        terminalDelta = 0.0,
        activeCoefficientIds = setOf(activeCoefficient),
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
                        }
                    )
                } else it
            },
        )
    }

    private class MemoryHeadRepository : WorldEquationHeadRepository {
        private var head: WorldEquationHead? = null

        override suspend fun load(): WorldEquationHead? = head

        override suspend fun compareAndSet(
            expectedRevision: Long?,
            next: WorldEquationHead,
        ): Boolean {
            if (head?.revision != expectedRevision) return false
            head = next
            return true
        }

        override suspend fun loadReport(): WorldEquationHeadLoadReport =
            WorldEquationHeadLoadReport(head, corrupted = false, message = null)
    }
}
