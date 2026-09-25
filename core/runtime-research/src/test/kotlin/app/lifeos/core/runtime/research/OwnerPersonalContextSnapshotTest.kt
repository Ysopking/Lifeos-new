package app.lifeos.core.runtime.research

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class OwnerPersonalContextSnapshotTest {
    @Test
    fun snapshotBindsWorldObjectiveAgencyAndSeinWithoutAuthorityCollapse() {
        val world = PersonalWorldMaterializer().materialize(
            semanticProjections = emptyList(),
            temporalEpisodes = emptyList(),
            asOf = NOW,
            revision = 1L,
        )
        val objective = OwnerObjectiveSnapshot.create(
            activeGoalPlanIds = listOf("goal-1"),
            objectiveFingerprints = listOf("objective-a"),
            constraintFingerprints = listOf("constraint-a"),
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

        val first = OwnerPersonalContextSnapshot.create(
            personalWorld = world,
            objective = objective,
            agency = agency,
            subjectiveState = subjective,
            asOf = NOW,
        )
        val second = OwnerPersonalContextSnapshot.create(
            personalWorld = world,
            objective = objective,
            agency = agency,
            subjectiveState = subjective,
            asOf = NOW,
        )

        assertEquals(first, second)
        assertFalse(first.factualWorldAuthority)
        assertFalse(first.policyAuthority)
        assertFalse(first.executionAuthority)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-25T08:15:00Z")
    }
}
