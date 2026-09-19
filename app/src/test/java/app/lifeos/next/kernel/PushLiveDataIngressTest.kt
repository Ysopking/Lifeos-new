package app.lifeos.next.kernel

import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.livedata.LiveDataAccountKey
import app.lifeos.core.runtime.livedata.LiveDataAccountObservation
import app.lifeos.core.runtime.livedata.LiveDataCapability
import app.lifeos.core.runtime.livedata.LiveDataConnectorId
import app.lifeos.core.runtime.livedata.LiveDataDelta
import app.lifeos.core.runtime.livedata.LiveDataDeltaOperation
import app.lifeos.core.runtime.livedata.LiveDataIngestResult
import app.lifeos.core.runtime.livedata.LiveDataPermission
import app.lifeos.core.runtime.livedata.LiveDataPermissionState
import app.lifeos.core.runtime.livedata.LiveDataStreamKind
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PushLiveDataIngressTest {
    @AfterTest
    fun clear() {
        PushLiveDataIngress.clearForTests()
    }

    @Test
    fun forwardsObservationAndDeltaWithoutOwningDurableState() = runTest {
        val connector = LiveDataConnectorId("android-notifications")
        val account = LiveDataAccountKey("device-notification-listener")
        val at = Instant.parse("2026-09-19T12:00:00Z")
        val observation = LiveDataAccountObservation(
            connectorId = connector,
            accountKey = account,
            capabilities = setOf(LiveDataCapability.MESSAGE_DELTAS),
            permissions = mapOf(
                LiveDataPermission.READ_MESSAGES to LiveDataPermissionState.GRANTED,
                LiveDataPermission.READ_CALENDAR to LiveDataPermissionState.UNAVAILABLE,
                LiveDataPermission.READ_FILES to LiveDataPermissionState.UNAVAILABLE,
            ),
            observedAt = at,
        )
        val delta = LiveDataDelta(
            connectorId = connector,
            accountKey = account,
            kind = LiveDataStreamKind.MESSAGE,
            externalId = "notification-1",
            externalVersion = "v1",
            operation = LiveDataDeltaOperation.UPSERT,
            occurredAt = at,
            observedAt = at,
            payload = "hello",
        )
        val expected = LiveDataIngestResult.Accepted(
            photonId = delta.photonId,
            permissionSnapshotId = PhotonId("permission"),
            permissionSnapshotRevision = 3,
            metadataPhotonId = PhotonId("metadata"),
        )
        var seenObservation: LiveDataAccountObservation? = null
        var seenDelta: LiveDataDelta? = null
        PushLiveDataIngress.install { currentObservation, currentDelta ->
            seenObservation = currentObservation
            seenDelta = currentDelta
            expected
        }

        val actual = PushLiveDataIngress.ingest(observation, delta)

        assertSame(expected, actual)
        assertEquals(observation, seenObservation)
        assertEquals(delta, seenDelta)
    }
}
