package app.lifeos.core.runtime.research

import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.runtime.world.StateDimensionId
import app.lifeos.core.runtime.world.WorldGap
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OwnerActionReadinessTest {
    @Test
    fun perceptionGapPrefersInformationAndGapFreeStateStillRequiresOwnerPolicy() {
        val context = context()
        val gap = WorldGap.Perception(
            domain = FieldDomainId("finance"),
            missingDimensions = setOf(StateDimensionId("finance.account.balance")),
            reason = "balance-missing",
        )

        val information = OwnerActionReadinessEvaluator().evaluate(
            context = context,
            openWorldGaps = listOf(gap),
        )
        val ready = OwnerActionReadinessEvaluator().evaluate(
            context = context,
            openWorldGaps = emptyList(),
        )

        assertEquals(
            OwnerActionReadinessState.INFORMATION_REQUIRED,
            information.state,
        )
        assertTrue(information.informationStepPreferred)
        assertFalse(information.executionAuthority)
        assertEquals(
            OwnerActionReadinessState.OWNER_POLICY_REVIEW_REQUIRED,
            ready.state,
        )
        assertTrue(ready.ownerPolicyReviewRequired)
        assertFalse(ready.policyAuthority)
    }

    private fun context(): OwnerPersonalContextSnapshot {
        val world = PersonalWorldMaterializer().materialize(
            semanticProjections = emptyList(),
            temporalEpisodes = emptyList(),
            asOf = NOW,
            revision = 1L,
        )
        val objective = OwnerObjectiveSnapshot.create(
            activeGoalPlanIds = emptyList(),
            objectiveFingerprints = emptyList(),
            constraintFingerprints = emptyList(),
            asOf = NOW,
        )
        val agency = OwnerAgencySnapshot.create(
            objective = objective,
            personalWorld = world,
            signals = emptyList(),
            asOf = NOW,
        )
        val subjective = SeinEvidenceIntegrator().infer(
            personalWorld = world,
            ownerAgency = agency,
            evidence = emptyList(),
            asOf = NOW,
        )
        return OwnerPersonalContextSnapshot.create(
            personalWorld = world,
            objective = objective,
            agency = agency,
            subjectiveState = subjective,
            asOf = NOW,
        )
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-25T09:00:00Z")
    }
}
