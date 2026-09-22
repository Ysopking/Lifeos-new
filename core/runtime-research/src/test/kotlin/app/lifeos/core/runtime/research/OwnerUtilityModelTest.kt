package app.lifeos.core.runtime.research

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OwnerUtilityModelTest {
    @Test
    fun unconfirmed_or_passively_inferred_preference_evidence_is_rejected() {
        assertFailsWith<IllegalArgumentException> {
            OwnerUtilityPreferenceObservation.create(
                dimension = OwnerUtilityDimension.OWNER_GOAL_UTILITY,
                importance = 1.0,
                confidence = 1.0,
                evidenceKind = OwnerUtilityEvidenceKind.EXPLICIT_FEEDBACK,
                sourceFingerprint = "a".repeat(64),
                ownerConfirmed = false,
            )
        }
    }

    @Test
    fun profile_is_deterministic_and_duplicate_exact_observations_are_idempotent() {
        val first = observation(
            OwnerUtilityDimension.CORRECTNESS,
            importance = 1.0,
            confidence = 1.0,
            source = 'a',
        )
        val second = observation(
            OwnerUtilityDimension.CORRECTNESS,
            importance = 0.5,
            confidence = 0.5,
            source = 'b',
        )
        val model = OwnerUtilityModel()

        val left = model.learn(listOf(first, second, first))
        val right = model.learn(listOf(second, first))

        assertEquals(left, right)
        assertEquals(2, left.sourceObservationIds.size)
        assertEquals(2, left.preference(OwnerUtilityDimension.CORRECTNESS)?.evidenceCount)
        assertEquals((1.0 * 1.0 + 0.5 * 0.5) / 1.5,
            left.preference(OwnerUtilityDimension.CORRECTNESS)?.meanImportance)
    }

    @Test
    fun benefit_and_cost_polarities_are_explicit_and_not_learned_from_behavior() {
        val profile = OwnerUtilityModel().learn(
            listOf(
                observation(
                    OwnerUtilityDimension.ROBUSTNESS,
                    importance = 0.8,
                    confidence = 1.0,
                    source = 'c',
                ),
                observation(
                    OwnerUtilityDimension.RISK_COST,
                    importance = 0.9,
                    confidence = 1.0,
                    source = 'd',
                ),
            )
        )

        assertEquals(
            OwnerUtilityPolarity.MAXIMIZE,
            profile.preference(OwnerUtilityDimension.ROBUSTNESS)?.polarity,
        )
        assertEquals(
            OwnerUtilityPolarity.MINIMIZE,
            profile.preference(OwnerUtilityDimension.RISK_COST)?.polarity,
        )
    }

    @Test
    fun unobserved_dimensions_remain_explicit_instead_of_receiving_guessed_weights() {
        val profile = OwnerUtilityModel().learn(
            listOf(
                observation(
                    OwnerUtilityDimension.LEARNING_VALUE,
                    importance = 0.7,
                    confidence = 0.8,
                    source = 'e',
                )
            )
        )

        assertEquals(
            setOf(OwnerUtilityDimension.LEARNING_VALUE),
            profile.preferences.map { it.dimension }.toSet(),
        )
        assertTrue(OwnerUtilityDimension.OWNER_GOAL_UTILITY in profile.unobservedDimensions)
        assertTrue(OwnerUtilityDimension.UNCERTAINTY_COST in profile.unobservedDimensions)
        assertNull(profile.preference(OwnerUtilityDimension.OWNER_GOAL_UTILITY))
    }

    @Test
    fun profile_grants_no_decision_execution_policy_or_promotion_authority() {
        val profile = OwnerUtilityModel().learn(emptyList())

        assertFalse(profile.decisionAuthority)
        assertFalse(profile.executionAuthority)
        assertFalse(profile.ownerPolicyAuthority)
        assertFalse(profile.promotionAuthority)
        assertEquals(OwnerUtilityDimension.entries, profile.unobservedDimensions)
    }

    @Test
    fun observation_identity_changes_with_dimension_weight_or_source() {
        val base = observation(
            OwnerUtilityDimension.REVERSIBILITY,
            importance = 0.6,
            confidence = 0.9,
            source = 'f',
        )
        val changedWeight = observation(
            OwnerUtilityDimension.REVERSIBILITY,
            importance = 0.7,
            confidence = 0.9,
            source = 'f',
        )
        val changedSource = observation(
            OwnerUtilityDimension.REVERSIBILITY,
            importance = 0.6,
            confidence = 0.9,
            source = '1',
        )

        assertTrue(base.id != changedWeight.id)
        assertTrue(base.id != changedSource.id)
    }

    private fun observation(
        dimension: OwnerUtilityDimension,
        importance: Double,
        confidence: Double,
        source: Char,
    ): OwnerUtilityPreferenceObservation =
        OwnerUtilityPreferenceObservation.create(
            dimension = dimension,
            importance = importance,
            confidence = confidence,
            evidenceKind = OwnerUtilityEvidenceKind.EXPLICIT_DECLARATION,
            sourceFingerprint = source.toString().repeat(64),
        )
}
