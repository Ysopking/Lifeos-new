package app.lifeos.core.runtime.world

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class PersonalContextSnapshotTest {
    @Test
    fun exactInputsProduceReplayStableSnapshotAndBootBinding() {
        val at = Instant.parse("2026-09-24T12:00:00Z")
        val first = PersonalContextSnapshot.create(
            appObservationHeadFingerprint = "obs-head",
            sensorProjectionFingerprint = "projection-head",
            financialStateFingerprint = "finance-head",
            relationshipStateFingerprint = "relationship-head",
            conversationStateFingerprint = "conversation-head",
            lifeGraphFingerprint = "life-head",
            evidenceHeadFingerprint = "evidence-head",
            ownerObservationPolicyRevision = 7L,
            createdAt = at,
        )
        val second = PersonalContextSnapshot.create(
            appObservationHeadFingerprint = "obs-head",
            sensorProjectionFingerprint = "projection-head",
            financialStateFingerprint = "finance-head",
            relationshipStateFingerprint = "relationship-head",
            conversationStateFingerprint = "conversation-head",
            lifeGraphFingerprint = "life-head",
            evidenceHeadFingerprint = "evidence-head",
            ownerObservationPolicyRevision = 7L,
            createdAt = at,
        )

        assertEquals(first, second)
        assertEquals(first.id, second.id)
        assertFalse(first.directWorldStateMutationAllowed)

        val binding = PersonalContextBootBinding(
            personalContextSnapshotId = first.id,
            sensorRegistryFingerprint = "sensor-registry-head",
            ownerObservationPolicyRevision = 7L,
        )
        assertFalse(binding.sensorPayloadAuthority)
    }

    @Test
    fun ownerObservationPolicyRevisionIsPartOfFrozenContextIdentity() {
        val at = Instant.parse("2026-09-24T12:00:00Z")
        val first = PersonalContextSnapshot.create(
            appObservationHeadFingerprint = "obs",
            sensorProjectionFingerprint = "projection",
            evidenceHeadFingerprint = "evidence",
            ownerObservationPolicyRevision = 4L,
            createdAt = at,
        )
        val second = PersonalContextSnapshot.create(
            appObservationHeadFingerprint = "obs",
            sensorProjectionFingerprint = "projection",
            evidenceHeadFingerprint = "evidence",
            ownerObservationPolicyRevision = 5L,
            createdAt = at,
        )

        assertNotEquals(first.id, second.id)
    }
}
