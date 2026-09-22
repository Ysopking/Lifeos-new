package app.lifeos.core.runtime.research

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OwnerAlignedDecisionUtilityTest {
    @Test
    fun owner_policy_block_dominates_high_utility() {
        val profile = profile(
            OwnerUtilityDimension.OWNER_GOAL_UTILITY to 1.0,
            OwnerUtilityDimension.CORRECTNESS to 1.0,
        )
        val candidate = candidate(
            measures = mapOf(
                OwnerUtilityDimension.OWNER_GOAL_UTILITY to 1.0,
                OwnerUtilityDimension.CORRECTNESS to 1.0,
            ),
            eligibility = OwnerPolicyEligibility.BLOCKED,
            policyFingerprint = "b".repeat(64),
            externalEffect = true,
        )

        val result = OwnerAlignedDecisionUtility().evaluate(profile, candidate)

        assertEquals(OwnerAlignedDecisionEvaluationState.POLICY_BLOCKED, result.state)
        assertNull(result.ownerAlignedUtility)
        assertFalse(result.selectionAuthority)
        assertFalse(result.executionAuthority)
    }

    @Test
    fun external_effect_candidate_requires_authoritative_policy_status() {
        assertFailsWith<IllegalArgumentException> {
            OwnerAlignedDecisionCandidate.create(
                goalPlanFingerprint = "a".repeat(64),
                epistemicDecisionFingerprint = "b".repeat(64),
                utilityMeasures = mapOf(
                    OwnerUtilityDimension.CORRECTNESS to 0.8,
                ),
                ownerPolicyEligibility = OwnerPolicyEligibility.NOT_REQUIRED,
                externalEffectRequired = true,
            )
        }
    }

    @Test
    fun missing_explicit_owner_dimension_stays_incomplete() {
        val profile = profile(
            OwnerUtilityDimension.CORRECTNESS to 1.0,
            OwnerUtilityDimension.ROBUSTNESS to 0.8,
        )
        val candidate = candidate(
            measures = mapOf(OwnerUtilityDimension.CORRECTNESS to 0.9),
        )

        val result = OwnerAlignedDecisionUtility().evaluate(profile, candidate)

        assertEquals(
            OwnerAlignedDecisionEvaluationState.INCOMPLETE_OWNER_UTILITY,
            result.state,
        )
        assertEquals(
            listOf(OwnerUtilityDimension.ROBUSTNESS),
            result.missingOwnerDimensions,
        )
        assertNull(result.ownerAlignedUtility)
    }

    @Test
    fun cost_dimensions_are_minimized_while_benefits_are_maximized() {
        val profile = profile(
            OwnerUtilityDimension.CORRECTNESS to 1.0,
            OwnerUtilityDimension.RISK_COST to 1.0,
        )
        val lowRisk = candidate(
            measures = mapOf(
                OwnerUtilityDimension.CORRECTNESS to 0.8,
                OwnerUtilityDimension.RISK_COST to 0.1,
            )
        )
        val highRisk = candidate(
            measures = mapOf(
                OwnerUtilityDimension.CORRECTNESS to 0.8,
                OwnerUtilityDimension.RISK_COST to 0.9,
            )
        )
        val evaluator = OwnerAlignedDecisionUtility()

        val low = evaluator.evaluate(profile, lowRisk)
        val high = evaluator.evaluate(profile, highRisk)

        assertEquals(OwnerAlignedDecisionEvaluationState.COMPLETE, low.state)
        assertEquals(OwnerAlignedDecisionEvaluationState.COMPLETE, high.state)
        assertTrue(requireNotNull(low.ownerAlignedUtility) > requireNotNull(high.ownerAlignedUtility))
        assertEquals(
            0.9,
            low.contributions.single { it.dimension == OwnerUtilityDimension.RISK_COST }.alignedMeasure,
        )
    }

    @Test
    fun evaluation_is_deterministic_and_does_not_select_a_winner() {
        val profile = profile(OwnerUtilityDimension.LEARNING_VALUE to 0.7)
        val first = candidate(
            goal = '1',
            measures = mapOf(OwnerUtilityDimension.LEARNING_VALUE to 0.6),
        )
        val second = candidate(
            goal = '2',
            measures = mapOf(OwnerUtilityDimension.LEARNING_VALUE to 0.9),
        )
        val evaluator = OwnerAlignedDecisionUtility()

        val left = evaluator.evaluateAll(profile, listOf(second, first, second))
        val right = evaluator.evaluateAll(profile, listOf(first, second))

        assertEquals(left, right)
        assertEquals(2, left.size)
        assertTrue(left.all { !it.selectionAuthority })
        assertTrue(left.all { !it.epistemicTruthAuthority })
        assertTrue(left.all { !it.ownerPolicyAuthority })
    }

    @Test
    fun empty_owner_profile_never_creates_a_default_utility() {
        val profile = OwnerUtilityModel().learn(emptyList())
        val candidate = candidate(
            measures = mapOf(OwnerUtilityDimension.CORRECTNESS to 1.0),
        )

        val result = OwnerAlignedDecisionUtility().evaluate(profile, candidate)

        assertEquals(
            OwnerAlignedDecisionEvaluationState.INCOMPLETE_OWNER_UTILITY,
            result.state,
        )
        assertNull(result.ownerAlignedUtility)
        assertEquals(
            OwnerUtilityDimension.entries.toList(),
            result.missingOwnerDimensions,
        )
    }

    private fun profile(
        vararg preferences: Pair<OwnerUtilityDimension, Double>,
    ): OwnerUtilityProfile =
        OwnerUtilityModel().learn(
            preferences.mapIndexed { index, (dimension, importance) ->
                OwnerUtilityPreferenceObservation.create(
                    dimension = dimension,
                    importance = importance,
                    confidence = 1.0,
                    evidenceKind = OwnerUtilityEvidenceKind.EXPLICIT_DECLARATION,
                    sourceFingerprint =
                        (index + 1).toString(16).padStart(64, '0'),
                )
            }
        )

    private fun candidate(
        goal: Char = 'a',
        measures: Map<OwnerUtilityDimension, Double>,
        eligibility: OwnerPolicyEligibility = OwnerPolicyEligibility.NOT_REQUIRED,
        policyFingerprint: String? = null,
        externalEffect: Boolean = false,
    ): OwnerAlignedDecisionCandidate =
        OwnerAlignedDecisionCandidate.create(
            goalPlanFingerprint = goal.toString().repeat(64),
            epistemicDecisionFingerprint = "e".repeat(64),
            utilityMeasures = measures,
            ownerPolicyEligibility = eligibility,
            ownerPolicyDecisionFingerprint = policyFingerprint,
            externalEffectRequired = externalEffect,
        )
}
