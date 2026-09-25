package app.lifeos.core.runtime.research

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class OwnerAgencySnapshotTest {
    @Test
    fun agencySnapshotIsDeterministicAndCarriesNoExecutionAuthority() {
        val world = PersonalWorldSnapshot(
            revision = 1L,
            asOf = NOW,
            financialStateFingerprint = "finance",
            relationshipStateFingerprint = null,
            conversationStateFingerprint = null,
            lifeGraphFingerprint = "life",
            sourceEvidenceIds = emptyList(),
            temporalEpisodeIds = emptyList(),
            fingerprint = app.lifeos.core.field.StableFieldIds.fingerprint(
                "personal-world-snapshot/v1",
                "1",
                NOW.toString(),
                "finance",
                "",
                "",
                "life",
            ),
        )
        val objective = OwnerObjectiveSnapshot.create(
            activeGoalPlanIds = listOf("goal-1"),
            objectiveFingerprints = listOf("objective-1"),
            constraintFingerprints = listOf("constraint-1"),
            asOf = NOW,
        )
        val weak = OwnerAgencySignal(
            dimension = OwnerAgencyDimension.INFORMATION,
            availability = 0.4,
            confidence = 0.4,
            evidenceIds = listOf("evidence-b"),
        )
        val strong = OwnerAgencySignal(
            dimension = OwnerAgencyDimension.INFORMATION,
            availability = 0.8,
            confidence = 0.9,
            evidenceIds = listOf("evidence-a"),
        )

        val first = OwnerAgencySnapshot.create(
            objective = objective,
            personalWorld = world,
            signals = listOf(weak, strong),
            asOf = NOW,
        )
        val second = OwnerAgencySnapshot.create(
            objective = objective,
            personalWorld = world,
            signals = listOf(strong, weak),
            asOf = NOW,
        )

        assertEquals(first, second)
        assertEquals(listOf(strong), first.signals)
        assertFalse(first.executionAuthority)
        assertFalse(first.ownerPolicyAuthority)
        assertFalse(objective.inferredPreferenceAuthority)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-25T06:30:00Z")
    }
}
