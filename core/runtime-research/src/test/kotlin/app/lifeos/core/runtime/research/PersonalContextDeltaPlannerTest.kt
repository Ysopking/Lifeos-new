package app.lifeos.core.runtime.research

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersonalContextDeltaPlannerTest {
    @Test
    fun identicalContextProducesNoDeltaAndChangedWorldRemainsNonAuthoritative() {
        val first = context(revision = 1L)
        val same = context(revision = 1L)
        val changed = context(revision = 2L)

        assertEquals(emptyList(), PersonalContextDeltaPlanner().diff(first, same))

        val deltas = PersonalContextDeltaPlanner().diff(first, changed)

        assertTrue(deltas.any { it.kind == PersonalContextDeltaKind.PERSONAL_WORLD })
        assertTrue(deltas.any { it.kind == PersonalContextDeltaKind.OWNER_AGENCY })
        assertTrue(deltas.any { it.kind == PersonalContextDeltaKind.SUBJECTIVE_STATE })
        assertFalse(deltas.any { it.executionAuthority })
        assertFalse(deltas.any { it.directWorldMutationAllowed })
    }

    private fun context(revision: Long): OwnerPersonalContextSnapshot {
        val world = PersonalWorldMaterializer().materialize(
            semanticProjections = emptyList(),
            temporalEpisodes = emptyList(),
            asOf = NOW,
            revision = revision,
        )
        val objective = OwnerObjectiveSnapshot.create(
            activeGoalPlanIds = listOf("goal-1"),
            objectiveFingerprints = listOf("objective-a"),
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
        val NOW: Instant = Instant.parse("2026-09-25T08:30:00Z")
    }
}
