package app.lifeos.next.kernel

import app.lifeos.core.runtime.life.AppObservationBatch
import app.lifeos.core.runtime.life.AppSensorBudget
import app.lifeos.core.runtime.life.AppSensorCursor
import app.lifeos.core.runtime.life.AuthorizedObservationPhotonCommitReceipt
import app.lifeos.core.runtime.life.ControlStatus
import app.lifeos.core.runtime.life.EpistemicStatus
import app.lifeos.core.runtime.life.InformationObservation
import app.lifeos.core.runtime.life.ObservationAuthorityClass
import app.lifeos.core.runtime.life.ObservationPrivacyClass
import app.lifeos.core.runtime.life.ObservationSurfaceKind
import app.lifeos.core.runtime.life.RealizationDescriptor
import app.lifeos.core.runtime.life.RepresentationLevel
import app.lifeos.core.runtime.life.SensorAttentionMode
import app.lifeos.core.runtime.life.SensorClass
import app.lifeos.core.runtime.life.SensorDescriptor
import app.lifeos.core.runtime.life.TemporalStatus
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidNotificationSensorBridgeTest {
    private val now = Instant.parse("2026-09-25T00:00:00Z")

    @Test
    fun activeNotificationUsesRegisteredB467SensorBatchPath() = runTest {
        val calls = mutableListOf<CapturedCall>()
        val bridge = AndroidNotificationSensorBridge { descriptor, cursor, budget, batch, scope, salience ->
            calls += CapturedCall(descriptor, cursor, budget, batch, scope, salience)
            receipt(descriptor)
        }
        bridge.applyAttention(SensorAttentionMode.FOCUSED)

        val first = observation("rev-1", "one")
        val second = observation("rev-2", "two")
        bridge.ingest(first)
        bridge.ingest(second)

        assertEquals(2, calls.size)
        assertEquals(SensorClass.NOTIFICATION, bridge.descriptor.sensorClass)
        assertEquals(
            PrivateOwnerObservationPolicyBaseline.NOTIFICATION_SENSOR_ID,
            bridge.descriptor.sensorId.value,
        )
        assertEquals(
            PrivateOwnerObservationPolicyBaseline.NOTIFICATION_RESOURCE_PREFIX,
            bridge.descriptor.resourcePrefix,
        )
        assertEquals(0L, calls[0].cursor.revision)
        assertEquals(1L, calls[0].batch.nextCursor.revision)
        assertEquals(listOf(first), calls[0].batch.observations)
        assertEquals(1L, calls[1].cursor.revision)
        assertEquals(2L, calls[1].batch.nextCursor.revision)
        assertEquals(listOf(second), calls[1].batch.observations)
        assertEquals(
            PrivateOwnerObservationPolicyBaseline.NOTIFICATION_SCOPE,
            calls[0].scope,
        )
        assertEquals(0.80, calls[0].salience)
        assertTrue(
            bridge.attentionCoverage.stateDimensions.any {
                it.value == "app.notification."
            }
        )
    }

    @Test
    fun suspendedNotificationSensorDoesNotAdvanceIngressCursor() = runTest {
        val revisions = mutableListOf<Long>()
        val bridge = AndroidNotificationSensorBridge { descriptor, cursor, _, _, _, _ ->
            revisions += cursor.revision
            receipt(descriptor)
        }

        bridge.applyAttention(SensorAttentionMode.SUSPENDED)
        bridge.ingest(observation("rev-suspended", "ignored"))
        assertTrue(revisions.isEmpty())

        bridge.applyAttention(SensorAttentionMode.EVENT_DRIVEN)
        bridge.ingest(observation("rev-live", "accepted"))
        assertEquals(listOf(0L), revisions)
    }

    private fun observation(
        revision: String,
        text: String,
    ) = InformationObservation(
        sourceId = PrivateOwnerObservationPolicyBaseline.NOTIFICATION_SENSOR_ID,
        sourceResource =
            PrivateOwnerObservationPolicyBaseline.NOTIFICATION_RESOURCE_PREFIX +
                "com.example:$revision",
        surface = ObservationSurfaceKind.NOTIFICATION,
        observedAt = now,
        sourceTimestamp = now,
        sourceRevision = revision,
        mimeType = "application/vnd.lifeos.android-notification+text",
        payload = text,
        realization = RealizationDescriptor(
            representation = RepresentationLevel.PROJECTED,
            epistemicStatus = EpistemicStatus.OBSERVED,
            temporalStatus = TemporalStatus.CURRENT,
            controlStatus = ControlStatus.PASSIVE,
        ),
        authority = ObservationAuthorityClass.PLATFORM_NOTIFICATION,
        privacy = ObservationPrivacyClass.PERSONAL,
        confidence = 1.0,
        tags = setOf("notification"),
    )

    private fun receipt(
        descriptor: SensorDescriptor,
    ) = AuthorizedObservationPhotonCommitReceipt(
        sensorId = descriptor.sensorId,
        authorizedBatchFingerprint = "0".repeat(64),
        committed = emptyList(),
        blocked = emptyList(),
    )

    private data class CapturedCall(
        val descriptor: SensorDescriptor,
        val cursor: AppSensorCursor,
        val budget: AppSensorBudget,
        val batch: AppObservationBatch,
        val scope: String,
        val salience: Double,
    )
}
