package app.lifeos.core.runtime.research

import app.lifeos.core.runtime.reasoning.KnowledgeGapKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class MetaReasoningLearnerTest {
    private val registry = ReasoningStrategyRegistry()
    private val strategy = registry.all().first {
        it.kind == ReasoningStrategyKind.STRUCTURAL_HYPOTHESIS_SEARCH
    }
    private val problemClass = ReasoningProblemClass.create(
        goalSemanticClass = "goal:solve",
        constraintCount = 2,
        factCount = 4,
        unknownCount = 3,
        assumptionCount = 1,
        gapKinds = listOf(
            KnowledgeGapKind.SEARCH_TRUNCATED,
            KnowledgeGapKind.NO_COMPLETE_REASONING_STATE,
        ),
    )

    @Test
    fun verified_outcome_requires_independent_verification_fingerprint() {
        assertFailsWith<IllegalArgumentException> {
            ReasoningStrategyOutcomeEpisode.create(
                strategy = strategy,
                problemClass = problemClass,
                sourceCycleId = "cycle-1",
                state = ReasoningStrategyOutcomeState.VERIFIED_SUCCESS,
                verificationFingerprint = null,
                normalizedEffort = 0.4,
            )
        }
    }

    @Test
    fun learner_is_deterministic_and_duplicate_episode_is_idempotent() {
        val success = episode(
            "cycle-success",
            ReasoningStrategyOutcomeState.VERIFIED_SUCCESS,
            0.4,
            0.8,
        )
        val failure = episode(
            "cycle-failure",
            ReasoningStrategyOutcomeState.VERIFIED_FAILURE,
            0.6,
            0.2,
        )
        val learner = MetaReasoningLearner(registry)

        val first = learner.learn(problemClass, listOf(success, failure, success))
        val second = learner.learn(problemClass, listOf(failure, success))

        assertEquals(first, second)
        val profile = first.single()
        assertEquals(1, profile.verifiedSuccessCount)
        assertEquals(1, profile.verifiedFailureCount)
        assertEquals(0.5, profile.empiricalSuccessRate)
        assertEquals(0.5, profile.meanVerifiedUncertaintyReduction)
        assertEquals(0.5, profile.meanNormalizedEffort)
        assertFalse(profile.selectionAuthority)
        assertFalse(profile.ownerUtilityAuthority)
        assertFalse(profile.promotionAuthority)
    }

    @Test
    fun inconclusive_episode_does_not_inflate_verified_rate() {
        val inconclusive = ReasoningStrategyOutcomeEpisode.create(
            strategy = strategy,
            problemClass = problemClass,
            sourceCycleId = "cycle-unknown",
            state = ReasoningStrategyOutcomeState.INCONCLUSIVE,
            normalizedEffort = 0.3,
            uncertaintyReduction = 0.9,
        )

        val profile = MetaReasoningLearner(registry)
            .learn(problemClass, listOf(inconclusive))
            .single()

        assertEquals(0, profile.verifiedSuccessCount)
        assertEquals(0, profile.verifiedFailureCount)
        assertEquals(1, profile.inconclusiveCount)
        assertNull(profile.empiricalSuccessRate)
        assertNull(profile.meanVerifiedUncertaintyReduction)
    }

    @Test
    fun another_problem_class_is_rejected_not_transferred() {
        val other = ReasoningProblemClass.create(
            goalSemanticClass = "goal:other",
            constraintCount = 2,
            factCount = 4,
            unknownCount = 3,
            assumptionCount = 1,
            gapKinds = listOf(KnowledgeGapKind.SEARCH_TRUNCATED),
        )
        val episode = episode(
            "cycle-a",
            ReasoningStrategyOutcomeState.VERIFIED_SUCCESS,
            0.2,
            0.4,
        )

        assertFailsWith<IllegalArgumentException> {
            MetaReasoningLearner(registry).learn(other, listOf(episode))
        }
    }

    @Test
    fun stale_strategy_descriptor_binding_is_rejected() {
        val episode = episode(
            "cycle-a",
            ReasoningStrategyOutcomeState.VERIFIED_SUCCESS,
            0.2,
            0.4,
        )
        val changed = ReasoningStrategyDescriptor.create(
            id = strategy.id.value.removePrefix(ReasoningStrategyId.PREFIX),
            version = strategy.version + "-changed",
            kind = strategy.kind,
            executionClass = strategy.executionClass,
            applicableGapKinds = strategy.applicableGapKinds,
            requiredEvidenceKinds = strategy.requiredEvidenceKinds,
            requiresExternalObservation = strategy.requiresExternalObservation,
        )
        val changedRegistry = ReasoningStrategyRegistry(
            registry.all().map { if (it.id == changed.id) changed else it }
        )

        assertFailsWith<IllegalArgumentException> {
            MetaReasoningLearner(changedRegistry).learn(problemClass, listOf(episode))
        }
    }

    private fun episode(
        cycle: String,
        state: ReasoningStrategyOutcomeState,
        effort: Double,
        reduction: Double,
    ): ReasoningStrategyOutcomeEpisode =
        ReasoningStrategyOutcomeEpisode.create(
            strategy = strategy,
            problemClass = problemClass,
            sourceCycleId = cycle,
            state = state,
            verificationFingerprint = when (state) {
                ReasoningStrategyOutcomeState.INCONCLUSIVE -> null
                else -> sha256("verify:$cycle")
            },
            normalizedEffort = effort,
            uncertaintyReduction = reduction,
        )

    private fun sha256(value: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
