package app.lifeos.core.runtime.evolution

import app.lifeos.core.runtime.world.CognitiveWorldEquationProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class WorldEquationEvidenceRepositoryTest {
    private val baseline = CognitiveWorldEquationProfile().spec
    private val first = baseline.stableCoefficients().first()
    private val candidate = baseline.copy(
        version = baseline.version + "-candidate",
        coefficients = baseline.coefficients.map {
            if (it.id == first.id) it.copy(
                multiplier = if (it.multiplier < 0.9) it.multiplier + 0.05 else it.multiplier - 0.05
            ) else it
        },
    )
    private val protocol = WorldEquationEvaluationProtocol(
        version = "evidence-test-v1",
        primaryMetric = WorldEquationPrimaryMetric.STABILIZATION_ITERATIONS,
        minimumIndependentRuns = 2,
        minimumDistinctWorkloads = 1,
        minimumActiveObservationsPerChangedCoefficient = 1,
    )
    private val policy = WorldEquationPromotionPolicy.V1

    @Test
    fun compareAndSetPreservesFrozenProtocolAndPolicy() = runTest {
        val repository = InMemoryWorldEquationEvidenceRepository()
        val evidence = WorldEquationEvidenceSet.empty(
            candidate,
            baseline,
            protocol,
            policy.fingerprint(),
        )
        val initial = WorldEquationEvidenceRecord.create(
            revision = 1L,
            state = WorldEquationLifecycleState.CONJECTURE,
            evidence = evidence,
        )
        assertTrue(repository.compareAndSet(candidate.fingerprint(), null, initial))

        val shadow = initial.transition(WorldEquationLifecycleState.SHADOW)
        assertTrue(repository.compareAndSet(candidate.fingerprint(), 1L, shadow))
        assertEquals(shadow, repository.load(candidate.fingerprint()))

        val changedPolicy = WorldEquationEvidenceRecord.create(
            revision = 3L,
            state = shadow.state,
            evidence = shadow.evidence.copy(policyFingerprint = "replacement-policy"),
            latestVerdictId = shadow.latestVerdictId,
            activationHeadFingerprint = shadow.activationHeadFingerprint,
            rollbackDecisionId = shadow.rollbackDecisionId,
            postActivationSafetyObservations = shadow.postActivationSafetyObservations,
        )
        assertFailsWith<IllegalArgumentException> {
            repository.compareAndSet(candidate.fingerprint(), 2L, changedPolicy)
        }
    }

    @Test
    fun staleRevisionCannotOverwriteEvidence() = runTest {
        val repository = InMemoryWorldEquationEvidenceRepository()
        val initial = WorldEquationEvidenceRecord.create(
            revision = 1L,
            state = WorldEquationLifecycleState.CONJECTURE,
            evidence = WorldEquationEvidenceSet.empty(
                candidate,
                baseline,
                protocol,
                policy.fingerprint(),
            ),
        )
        assertTrue(repository.compareAndSet(candidate.fingerprint(), null, initial))
        val shadow = initial.transition(WorldEquationLifecycleState.SHADOW)
        assertTrue(repository.compareAndSet(candidate.fingerprint(), 1L, shadow))
        val validNextRevision = shadow.recordVerdict("verdict:stale-write")
        assertFalse(
            repository.compareAndSet(
                candidate.fingerprint(),
                1L,
                validNextRevision,
            )
        )
    }
}
