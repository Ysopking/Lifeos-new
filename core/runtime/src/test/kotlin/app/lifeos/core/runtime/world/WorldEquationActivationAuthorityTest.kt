package app.lifeos.core.runtime.world

import app.lifeos.core.field.world.WorldEquationSpec
import app.lifeos.core.runtime.evolution.InMemoryWorldEquationEvidenceRepository
import app.lifeos.core.runtime.evolution.WorldEquationEvidenceCoordinator
import app.lifeos.core.runtime.evolution.WorldEquationEvidencePartition
import app.lifeos.core.runtime.evolution.WorldEquationEvaluationProtocol
import app.lifeos.core.runtime.evolution.WorldEquationEvolutionAdmissionGate
import app.lifeos.core.runtime.evolution.WorldEquationPrimaryMetric
import app.lifeos.core.runtime.evolution.WorldEquationPromotionAdmission
import app.lifeos.core.runtime.evolution.WorldEquationPromotionEvaluator
import app.lifeos.core.runtime.evolution.WorldEquationPromotionPolicy
import app.lifeos.core.runtime.evolution.WorldEquationRunMetrics
import app.lifeos.core.runtime.evolution.WorldEquationShadowObservation
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WorldEquationActivationAuthorityTest {
    @Test
    fun baselineIsSeededThenEvidenceBoundPhysicsBecomesActiveByCas() = runBlocking {
        val baseline = CognitiveWorldEquationProfile().spec
        val candidate = changedCandidate(baseline, "lifeos-world-cognitive-v2")
        val fixture = admissionFixture(baseline, candidate)
        val registry = InMemoryWorldEquationRegistry(listOf(baseline))
        val heads = MemoryHeadRepository()
        val authority = WorldEquationActivationAuthority(
            equations = registry,
            heads = heads,
            baseline = baseline,
            admissionVerifier = fixture.gate,
        )

        assertEquals(baseline.version, authority.activeVersion())
        val seeded = requireNotNull(heads.load())
        assertEquals(1L, seeded.revision)
        assertNull(seeded.predecessorEquationVersion)

        val promoted = authority.promote(
            candidate = candidate,
            admission = fixture.admission,
            expectedHeadFingerprint = seeded.fingerprint,
        )

        assertEquals(2L, promoted.revision)
        assertEquals(candidate.version, promoted.activeEquationVersion)
        assertEquals(baseline.version, promoted.predecessorEquationVersion)
        assertEquals(
            fixture.admission.validation.promotionDecisionId,
            promoted.sourcePromotionId,
        )
        assertEquals(candidate.version, authority.activeVersion())
    }

    @Test
    fun rollbackRestoresExactRegisteredPredecessorAfterRehydration() = runBlocking {
        val baseline = CognitiveWorldEquationProfile().spec
        val candidate = changedCandidate(baseline, "lifeos-world-cognitive-v2")
        val fixture = admissionFixture(baseline, candidate)
        val registry = InMemoryWorldEquationRegistry(listOf(baseline, candidate))
        val heads = MemoryHeadRepository()
        val specs = InMemoryWorldEquationSpecRepository()
        val first = WorldEquationActivationAuthority(
            equations = registry,
            heads = heads,
            baseline = baseline,
            specs = specs,
            admissionVerifier = fixture.gate,
        )

        assertEquals(baseline.version, first.activeVersion())
        val baselineHead = first.activeHead()
        first.promote(
            candidate = candidate,
            admission = fixture.admission,
            expectedHeadFingerprint = baselineHead.fingerprint,
        )
        assertEquals(candidate.version, first.activeVersion())

        val rehydrated = WorldEquationActivationAuthority(
            equations = InMemoryWorldEquationRegistry(listOf(baseline)),
            heads = heads,
            baseline = baseline,
            specs = specs,
        )
        val restored = rehydrated.rollbackToPredecessor(
            expectedCurrentVersion = candidate.version,
            rollbackDecisionId = "decision:test-rollback",
        )

        assertEquals(3L, restored.revision)
        assertEquals(baseline.version, restored.activeEquationVersion)
        assertEquals(candidate.version, restored.predecessorEquationVersion)
        assertEquals("rollback:decision:test-rollback", restored.sourcePromotionId)
        assertEquals(baseline.version, rehydrated.activeVersion())
    }

    @Test
    fun promotionAdmissionCannotBeReusedForDifferentPhysics() = runBlocking {
        val baseline = CognitiveWorldEquationProfile().spec
        val candidate = changedCandidate(baseline, "lifeos-world-cognitive-v2")
        val other = changedCandidate(baseline, "lifeos-world-cognitive-v3", delta = 0.08)
        val fixture = admissionFixture(baseline, candidate)
        val authority = WorldEquationActivationAuthority(
            equations = InMemoryWorldEquationRegistry(listOf(baseline)),
            heads = MemoryHeadRepository(),
            baseline = baseline,
            admissionVerifier = fixture.gate,
        )

        assertFailsWith<IllegalArgumentException> {
            authority.promote(
                candidate = other,
                admission = fixture.admission,
                expectedHeadFingerprint = authority.activeHead().fingerprint,
            )
        }
    }

    @Test
    fun stalePromotionIntentCannotBeReplayedAfterHeadHistoryChanges() = runBlocking {
        val baseline = CognitiveWorldEquationProfile().spec
        val candidate = changedCandidate(baseline, "lifeos-world-cognitive-v2")
        val fixture = admissionFixture(baseline, candidate)
        val registry = InMemoryWorldEquationRegistry(listOf(baseline, candidate))
        val heads = MemoryHeadRepository()
        val specs = InMemoryWorldEquationSpecRepository()
        val authority = WorldEquationActivationAuthority(
            equations = registry,
            heads = heads,
            baseline = baseline,
            specs = specs,
            admissionVerifier = fixture.gate,
        )
        val originalHead = authority.activeHead()
        authority.promote(
            candidate = candidate,
            admission = fixture.admission,
            expectedHeadFingerprint = originalHead.fingerprint,
        )
        authority.rollbackToPredecessor(
            expectedCurrentVersion = candidate.version,
            rollbackDecisionId = "decision:replay-rollback",
        )

        assertFailsWith<IllegalArgumentException> {
            authority.promote(
                candidate = candidate,
                admission = fixture.admission,
                expectedHeadFingerprint = originalHead.fingerprint,
            )
        }
    }

    @Test
    fun versionOnlyChangeIsNotNewPhysics() = runBlocking {
        val baseline = CognitiveWorldEquationProfile().spec
        val candidate = baseline.copy(version = "lifeos-world-cognitive-v2")
        val gate = WorldEquationEvolutionAdmissionGate(
            InMemoryWorldEquationEvidenceRepository(),
            WorldEquationPromotionEvaluator(),
        )

        assertEquals(baseline.physicsFingerprint(), candidate.physicsFingerprint())
        assertFailsWith<IllegalArgumentException> {
            gate.admit(candidate, baseline)
        }
    }

    @Test
    fun explanationOnlyChangeIsNotNewPhysics() = runBlocking {
        val baseline = CognitiveWorldEquationProfile().spec
        val first = baseline.stableCoefficients().first()
        val candidate = baseline.copy(
            version = "lifeos-world-cognitive-v2",
            coefficients = baseline.coefficients.map {
                if (it.id == first.id) it.copy(explanation = it.explanation + " clarified") else it
            },
        )
        val gate = WorldEquationEvolutionAdmissionGate(
            InMemoryWorldEquationEvidenceRepository(),
            WorldEquationPromotionEvaluator(),
        )

        assertEquals(baseline.physicsFingerprint(), candidate.physicsFingerprint())
        assertFailsWith<IllegalArgumentException> {
            gate.admit(candidate, baseline)
        }
    }

    private suspend fun admissionFixture(
        baseline: WorldEquationSpec,
        candidate: WorldEquationSpec,
    ): AdmissionFixture {
        val repository = InMemoryWorldEquationEvidenceRepository()
        val policy = WorldEquationPromotionPolicy(
            version = "activation-test-policy-v1",
            primaryImprovementMargin = 0.0,
            statusNonInferiorityMargin = 0.0,
            minimumImprovedRunFraction = 0.60,
            workloadNonInferiorityMargin = 0.0,
            minimumCoefficientNormRatio = 0.20,
        )
        val evaluator = WorldEquationPromotionEvaluator(policy)
        val coordinator = WorldEquationEvidenceCoordinator(repository, evaluator)
        val protocol = WorldEquationEvaluationProtocol(
            version = "activation-test-protocol-v1",
            primaryMetric = WorldEquationPrimaryMetric.STABILIZATION_ITERATIONS,
            minimumIndependentRuns = 2,
            minimumDistinctWorkloads = 1,
            minimumActiveObservationsPerChangedCoefficient = 1,
        )
        coordinator.beginShadow(candidate, baseline, protocol)
        val activeId = baseline.stableCoefficients().first().id
        coordinator.recordObservation(
            candidate,
            baseline,
            observation(baseline, candidate, activeId, "run-1"),
        )
        coordinator.recordObservation(
            candidate,
            baseline,
            observation(
                baseline,
                candidate,
                activeId,
                "run-2",
                WorldEquationEvidencePartition.HOLDOUT,
            ),
        )
        val gate = WorldEquationEvolutionAdmissionGate(repository, evaluator)
        return AdmissionFixture(
            gate = gate,
            admission = gate.admit(candidate, baseline),
        )
    }

    private fun observation(
        baseline: WorldEquationSpec,
        candidate: WorldEquationSpec,
        activeId: app.lifeos.core.field.world.WorldCoefficientId,
        runId: String,
        partition: WorldEquationEvidencePartition = WorldEquationEvidencePartition.SHADOW,
    ) = WorldEquationShadowObservation(
        caseFingerprint = "case:" + runId,
        runId = runId,
        workloadId = "activation-test-workload",
        partition = partition,
        baselineEquationFingerprint = baseline.fingerprint(),
        candidateEquationFingerprint = candidate.fingerprint(),
        baseline = WorldEquationRunMetrics(
            status = WorldFormulaStatus.CONVERGED,
            iterationCount = 5,
            conflictCount = 0,
            anomalyCount = 0,
            terminalDelta = 0.0,
            activeCoefficientIds = setOf(activeId),
        ),
        candidate = WorldEquationRunMetrics(
            status = WorldFormulaStatus.CONVERGED,
            iterationCount = 3,
            conflictCount = 0,
            anomalyCount = 0,
            terminalDelta = 0.0,
            activeCoefficientIds = setOf(activeId),
        ),
    )

    private fun changedCandidate(
        baseline: WorldEquationSpec,
        version: String,
        delta: Double = 0.05,
    ): WorldEquationSpec {
        val first = baseline.stableCoefficients().first()
        val nextMultiplier = if (first.multiplier + delta <= 1.0) {
            first.multiplier + delta
        } else {
            first.multiplier - delta
        }
        return baseline.copy(
            version = version,
            coefficients = baseline.coefficients.map {
                if (it.id == first.id) it.copy(multiplier = nextMultiplier) else it
            },
        )
    }

    private data class AdmissionFixture(
        val gate: WorldEquationEvolutionAdmissionGate,
        val admission: WorldEquationPromotionAdmission,
    )

    private class MemoryHeadRepository : WorldEquationHeadRepository {
        private var head: WorldEquationHead? = null

        override suspend fun load(): WorldEquationHead? = head

        override suspend fun compareAndSet(
            expectedRevision: Long?,
            next: WorldEquationHead,
        ): Boolean {
            if (head?.revision != expectedRevision) return false
            require(next.revision == (expectedRevision ?: 0L) + 1L)
            require(next.predecessorEquationVersion == head?.activeEquationVersion)
            head = next
            return true
        }

        override suspend fun loadReport(): WorldEquationHeadLoadReport =
            WorldEquationHeadLoadReport(
                head = head,
                corrupted = false,
                message = null,
            )
    }
}
