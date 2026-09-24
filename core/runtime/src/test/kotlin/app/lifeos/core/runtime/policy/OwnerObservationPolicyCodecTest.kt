package app.lifeos.core.runtime.policy

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class OwnerObservationPolicyCodecTest {
    @Test
    fun segmentedRoundTripPreservesGrant() {
        val grant = OwnerObservationGrant.create(
            actorId = OwnerActorId("owner"),
            observationType = OwnerObservationType.NOTIFICATION,
            resource = OwnerResourceSelector(
                OwnerResourceSelectorType.PREFIX,
                "android-notification:",
            ),
            scope = "notification-scope",
            sensorId = "android-notification-listener",
            validFrom = Instant.EPOCH,
        )
        val event = OwnerObservationPolicyEvent(
            revision = 7L,
            type = OwnerObservationPolicyEventType.GRANT,
            recordedAt = Instant.parse("2026-09-24T12:00:00Z"),
            grant = grant,
        )

        assertEquals(
            event,
            OwnerObservationPolicyEventLogCodec.decodeSegment(
                OwnerObservationPolicyEventLogCodec.encodeSegment(event)
            )
        )
    }
}
