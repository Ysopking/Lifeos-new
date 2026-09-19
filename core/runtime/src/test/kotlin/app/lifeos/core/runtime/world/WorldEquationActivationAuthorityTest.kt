package app.lifeos.core.runtime.world

import app.lifeos.core.runtime.evolution.WorldEquationEvolutionAdmissionGate
import app.lifeos.core.runtime.evolution.WorldEquationEvolutionValidation
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WorldEquationActivationAuthorityTest {
    @Test
    fun baselineIsSeededThenPromotedPhysicsBecomesActiveByCas() = runBlocking {
        val baseline = CognitiveWorldEquationProfile().spec
        val registry = InMemoryWorldEquationRegistry(listOf(baseline))
        val heads = MemoryHeadRepository()
        val authority = WorldEquationActivationAuthority(
            equations = registry,
            heads = heads,
            baseline = baseline,
        )

        assertEquals(baseline.version, authority.activeVersion())
        val seeded = requireNotNull(heads.load())
        assertEquals(1L, seeded.revision)
        assertNull(seeded.predecessorEquationVersion)

        val candidate = changedCandidate(baseline, "lifeos-world-cognitive-v2")
        val admission = WorldEquationEvolutionAdmissionGate.admit(
            candidate = candidate,
            baseline = baseline,
            validation = WorldEquationEvolutionValidation(
                holdoutEvidenceId = "holdout:test-v2",
                shadowEvidenceId = "shadow:test-v2",
                trialEvidenceId = "trial:test-v2",
                promotionDecisionId = "promotion:test-v2",
            ),
        )
        val promoted = authority.promote(
            candidate = candidate,
            admission = admission,
        )

        assertEquals(2L, promoted.revision)
        assertEquals(candidate.version, promoted.activeEquationVersion)
        assertEquals(baseline.version, promoted.predecessorEquationVersion)
        assertEquals("promotion:test-v2", promoted.sourcePromotionId)
        assertEquals(candidate.version, authority.activeVersion())
    }

    @Test
    fun rollbackRestoresExactRegisteredPredecessorAfterRehydration() = runBlocking {
        val baseline = CognitiveWorldEquationProfile().spec
        val candidate = changedCandidate(baseline, "lifeos-world-cognitive-v2")
        val registry = InMemoryWorldEquationRegistry(listOf(baseline, candidate))
        val heads = MemoryHeadRepository()
        val specs = InMemoryWorldEquationSpecRepository()
        val first = WorldEquationActivationAuthority(
            equations = registry,
            heads = heads,
            baseline = baseline,
            specs = specs,
        )

        assertEquals(baseline.version, first.activeVersion())
        first.promote(
            candidate = candidate,
            admission = WorldEquationEvolutionAdmissionGate.admit(
                candidate = candidate,
                baseline = baseline,
                validation = WorldEquationEvolutionValidation(
                    holdoutEvidenceId = "holdout:test-v2",
                    shadowEvidenceId = "shadow:test-v2",
                    trialEvidenceId = "trial:test-v2",
                    promotionDecisionId = "promotion:test-v2",
                ),
            ),
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
        val authority = WorldEquationActivationAuthority(
            equations = InMemoryWorldEquationRegistry(listOf(baseline)),
            heads = MemoryHeadRepository(),
            baseline = baseline,
        )
        val admission = WorldEquationEvolutionAdmissionGate.admit(
            candidate = candidate,
            baseline = baseline,
            validation = WorldEquationEvolutionValidation(
                holdoutEvidenceId = "holdout:test-v2",
                shadowEvidenceId = "shadow:test-v2",
                trialEvidenceId = "trial:test-v2",
                promotionDecisionId = "promotion:test-v2",
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            authority.promote(
                candidate = other,
                admission = admission,
            )
        }
    }


    @Test
    fun versionOnlyChangeIsNotNewPhysics() {
        val baseline = CognitiveWorldEquationProfile().spec
        val candidate = baseline.copy(version = "lifeos-world-cognitive-v2")

        assertEquals(baseline.physicsFingerprint(), candidate.physicsFingerprint())
        assertFailsWith<IllegalArgumentException> {
            WorldEquationEvolutionAdmissionGate.admit(
                candidate = candidate,
                baseline = baseline,
                validation = validation("version-only"),
            )
        }
    }

    @Test
    fun explanationOnlyChangeIsNotNewPhysics() {
        val baseline = CognitiveWorldEquationProfile().spec
        val first = baseline.stableCoefficients().first()
        val candidate = baseline.copy(
            version = "lifeos-world-cognitive-v2",
            coefficients = baseline.coefficients.map {
                if (it.id == first.id) it.copy(explanation = it.explanation + " clarified") else it
            },
        )

        assertEquals(baseline.physicsFingerprint(), candidate.physicsFingerprint())
        assertFailsWith<IllegalArgumentException> {
            WorldEquationEvolutionAdmissionGate.admit(
                candidate = candidate,
                baseline = baseline,
                validation = validation("explanation-only"),
            )
        }
    }


    private fun changedCandidate(
        baseline: app.lifeos.core.field.world.WorldEquationSpec,
        version: String,
        delta: Double = 0.05,
    ): app.lifeos.core.field.world.WorldEquationSpec {
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

    private fun validation(suffix: String) = WorldEquationEvolutionValidation(
        holdoutEvidenceId = "holdout:$suffix",
        shadowEvidenceId = "shadow:$suffix",
        trialEvidenceId = "trial:$suffix",
        promotionDecisionId = "promotion:$suffix",
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
