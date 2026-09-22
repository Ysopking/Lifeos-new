package app.lifeos.core.runtime.research

import app.lifeos.core.runtime.level7.StructuralSignature
import app.lifeos.core.runtime.level7.StructuralTransferCandidate
import app.lifeos.core.runtime.reasoning.KnowledgeGapKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrossDomainTransferEngineTest {
    private val registry = ReasoningStrategyRegistry()
    private val descriptor = requireNotNull(
        registry.all().firstOrNull {
            it.kind == ReasoningStrategyKind.STRUCTURAL_HYPOTHESIS_SEARCH
        }
    )

    @Test
    fun cross_domain_requirement_is_enforced() {
        val sourceProblem = problem("source")
        val targetProblem = problem("target")
        val profile = profile(sourceProblem, listOf(true, true, false))
        val sameDomainSource = signature("domain-x", "topology", "relations", "dimensions")
        val sameDomainTarget = signature("domain-x", "topology", "relations", "dimensions")

        assertFailsWith<IllegalArgumentException> {
            engine().propose(
                sourceProfile = profile,
                sourceProblemClass = sourceProblem,
                targetProblemClass = targetProblem,
                sourceSignature = sameDomainSource,
                targetSignature = sameDomainTarget,
            )
        }
    }

    @Test
    fun exact_structural_candidate_binding_rejects_source_or_target_substitution() {
        val sourceProblem = problem("source")
        val targetProblem = problem("target")
        val profile = profile(sourceProblem, listOf(true, true, false))
        val source = signature("domain-x", "topology", "relations", "dimensions")
        val target = signature("domain-y", "topology", "relations", "dimensions")
        val substituted = StructuralTransferCandidate.create(
            source = signature("domain-other", "topology", "relations", "dimensions"),
            target = target,
            structuralSimilarity = 1.0,
            validationFingerprint = "validation",
        )

        assertFailsWith<IllegalArgumentException> {
            engine().proposeFromCandidate(
                sourceProfile = profile,
                sourceProblemClass = sourceProblem,
                targetProblemClass = targetProblem,
                sourceSignature = source,
                targetSignature = target,
                candidate = substituted,
            )
        }
    }

    @Test
    fun hypothesis_preserves_no_semantic_identity_and_no_activation_or_promotion_authority() {
        val sourceProblem = problem("source")
        val targetProblem = problem("target")
        val profile = profile(sourceProblem, listOf(true, true, false))
        val hypothesis = assertNotNull(
            engine().propose(
                sourceProfile = profile,
                sourceProblemClass = sourceProblem,
                targetProblemClass = targetProblem,
                sourceSignature = signature("domain-x", "t", "r", "d"),
                targetSignature = signature("domain-y", "t", "r", "d"),
            )
        )

        assertFalse(hypothesis.semanticIdentityEstablished)
        assertFalse(hypothesis.directActivationAllowed)
        assertFalse(hypothesis.promotionAuthority)
        assertFalse(hypothesis.selectionAuthority)
        assertEquals(
            CrossDomainTransferValidationStatus.TARGET_EVIDENCE_REQUIRED,
            hypothesis.validationStatus,
        )
    }

    @Test
    fun below_threshold_structural_similarity_produces_no_hypothesis() {
        val sourceProblem = problem("source")
        val targetProblem = problem("target")
        val profile = profile(sourceProblem, listOf(true, false, true))

        val hypothesis = engine(minimumSimilarity = 0.8).propose(
            sourceProfile = profile,
            sourceProblemClass = sourceProblem,
            targetProblemClass = targetProblem,
            sourceSignature = signature("domain-x", "same-topology", "source-rel", "source-dim"),
            targetSignature = signature("domain-y", "same-topology", "target-rel", "target-dim"),
        )

        assertNull(hypothesis)
    }

    @Test
    fun transferred_prior_can_only_reduce_source_empirical_support() {
        val sourceProblem = problem("source")
        val targetProblem = problem("target")
        val profile = profile(sourceProblem, listOf(true, true, true, false))
        val sourceRate = requireNotNull(profile.empiricalSuccessRate)
        val hypothesis = assertNotNull(
            engine().propose(
                sourceProfile = profile,
                sourceProblemClass = sourceProblem,
                targetProblemClass = targetProblem,
                sourceSignature = signature("domain-x", "t", "r", "d"),
                targetSignature = signature("domain-y", "t", "r", "d"),
            )
        )

        assertTrue(hypothesis.transferredSuccessPrior <= sourceRate)
        assertTrue(hypothesis.transferredSuccessPrior < sourceRate)
        assertEquals(1.0, hypothesis.structuralSimilarity)
        assertEquals(4, hypothesis.sourceVerifiedSampleCount)
    }

    @Test
    fun inconclusive_only_source_profile_cannot_seed_transfer() {
        val sourceProblem = problem("source")
        val targetProblem = problem("target")
        val profile = profile(
            sourceProblem,
            verified = emptyList(),
            inconclusiveCount = 3,
        )

        assertFailsWith<IllegalArgumentException> {
            engine().propose(
                sourceProfile = profile,
                sourceProblemClass = sourceProblem,
                targetProblemClass = targetProblem,
                sourceSignature = signature("domain-x", "t", "r", "d"),
                targetSignature = signature("domain-y", "t", "r", "d"),
            )
        }
    }

    @Test
    fun target_validation_requires_exact_b384_target_profile_and_minimum_verified_samples() {
        val sourceProblem = problem("source")
        val targetProblem = problem("target")
        val sourceProfile = profile(sourceProblem, listOf(true, true, false))
        val engine = engine(requiredTargetSamples = 3)
        val hypothesis = assertNotNull(
            engine.propose(
                sourceProfile = sourceProfile,
                sourceProblemClass = sourceProblem,
                targetProblemClass = targetProblem,
                sourceSignature = signature("domain-x", "t", "r", "d"),
                targetSignature = signature("domain-y", "t", "r", "d"),
            )
        )

        val insufficient = profile(targetProblem, listOf(true, false))
        val required = engine.validateTargetEvidence(hypothesis, insufficient)
        assertEquals(
            CrossDomainTransferValidationStatus.TARGET_EVIDENCE_REQUIRED,
            required.status,
        )
        assertNull(required.targetPerformanceProfileFingerprint)

        val sufficient = profile(targetProblem, listOf(true, true, false))
        val validated = engine.validateTargetEvidence(hypothesis, sufficient)
        assertEquals(
            CrossDomainTransferValidationStatus.TARGET_EVIDENCE_SATISFIED,
            validated.status,
        )
        assertEquals(sufficient.fingerprint, validated.targetPerformanceProfileFingerprint)
        assertFalse(validated.activationAuthority)
        assertFalse(validated.promotionAuthority)

        val unrelatedTarget = profile(problem("other-target"), listOf(true, true, true))
        assertFailsWith<IllegalArgumentException> {
            engine.validateTargetEvidence(hypothesis, unrelatedTarget)
        }
    }

    @Test
    fun episode_input_order_does_not_change_profile_or_transfer_identity() {
        val sourceProblem = problem("source")
        val targetProblem = problem("target")
        val episodes = episodes(sourceProblem, listOf(true, false, true, true))
        val learner = MetaReasoningLearner(registry)
        val firstProfile = learner.learn(sourceProblem, episodes).single()
        val secondProfile = learner.learn(sourceProblem, episodes.reversed()).single()
        assertEquals(firstProfile, secondProfile)

        val engine = engine()
        val source = signature("domain-x", "t", "r", "d")
        val target = signature("domain-y", "t", "r", "d")
        val first = assertNotNull(
            engine.propose(firstProfile, sourceProblem, targetProblem, source, target)
        )
        val second = assertNotNull(
            engine.propose(secondProfile, sourceProblem, targetProblem, source, target)
        )

        assertEquals(first.fingerprint, second.fingerprint)
        assertEquals(first.structuralTransferCandidateId, second.structuralTransferCandidateId)
    }

    private fun engine(
        minimumSimilarity: Double = 2.0 / 3.0,
        requiredTargetSamples: Int = 3,
    ): CrossDomainTransferEngine =
        CrossDomainTransferEngine(
            registry = registry,
            minimumStructuralSimilarity = minimumSimilarity,
            requiredTargetValidationSamples = requiredTargetSamples,
        )

    private fun problem(goalClass: String): ReasoningProblemClass =
        ReasoningProblemClass.create(
            goalSemanticClass = goalClass,
            constraintCount = 2,
            factCount = 3,
            unknownCount = 1,
            assumptionCount = 0,
            gapKinds = listOf(KnowledgeGapKind.SEARCH_TRUNCATED),
        )

    private fun profile(
        problem: ReasoningProblemClass,
        verified: List<Boolean>,
        inconclusiveCount: Int = 0,
    ): ReasoningStrategyPerformanceProfile {
        val learner = MetaReasoningLearner(registry)
        return learner.learn(
            problem,
            episodes(problem, verified, inconclusiveCount),
        ).single()
    }

    private fun episodes(
        problem: ReasoningProblemClass,
        verified: List<Boolean>,
        inconclusiveCount: Int = 0,
    ): List<ReasoningStrategyOutcomeEpisode> {
        val out = mutableListOf<ReasoningStrategyOutcomeEpisode>()
        verified.forEachIndexed { index, success ->
            out += ReasoningStrategyOutcomeEpisode.create(
                strategy = descriptor,
                problemClass = problem,
                sourceCycleId = "verified-cycle-$index-${problem.fingerprint.take(8)}",
                state =
                    if (success) {
                        ReasoningStrategyOutcomeState.VERIFIED_SUCCESS
                    } else {
                        ReasoningStrategyOutcomeState.VERIFIED_FAILURE
                    },
                verificationFingerprint =
                    (if (success) "a" else "b").repeat(64),
                normalizedEffort = 0.25 + index * 0.05,
                uncertaintyReduction = if (success) 0.6 else 0.1,
            )
        }
        repeat(inconclusiveCount) { index ->
            out += ReasoningStrategyOutcomeEpisode.create(
                strategy = descriptor,
                problemClass = problem,
                sourceCycleId = "inconclusive-cycle-$index-${problem.fingerprint.take(8)}",
                state = ReasoningStrategyOutcomeState.INCONCLUSIVE,
                normalizedEffort = 0.4,
                uncertaintyReduction = null,
            )
        }
        return out
    }

    private fun signature(
        domain: String,
        topology: String,
        relation: String,
        dimension: String,
    ): StructuralSignature =
        StructuralSignature(
            domainId = domain,
            topologyFingerprint = topology,
            relationFingerprint = relation,
            dimensionFingerprint = dimension,
        )
}
