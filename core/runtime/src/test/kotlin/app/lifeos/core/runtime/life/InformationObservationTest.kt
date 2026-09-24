package app.lifeos.core.runtime.life

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class InformationObservationTest {
    private val observedAt = Instant.parse("2026-09-24T12:00:00Z")

    @Test
    fun metadataOrderingDoesNotChangeObservationIdentity() {
        val first = notification(
            metadata = linkedMapOf("package" to "example.app", "category" to "msg")
        )
        val second = notification(
            metadata = linkedMapOf("category" to "msg", "package" to "example.app")
        )

        assertEquals(first.id, second.id)
        assertEquals(first.sourceObservationFingerprint, second.sourceObservationFingerprint)
    }

    @Test
    fun authorizationBindsProvenanceWithoutRewritingObservedEventIdentity() {
        val raw = notification()
        val authorized = raw.authorizedBy("owner-observation-grant:abc")

        assertEquals(raw.id, authorized.id)
        assertEquals(raw.sourceObservationFingerprint, authorized.sourceObservationFingerprint)
        assertNotEquals(raw.provenanceFingerprint, authorized.provenanceFingerprint)
        assertTrue(
            authorized.toPerceptionSignal().tags.contains(
                "observation-grant:owner-observation-grant:abc"
            )
        )
    }

    @Test
    fun notificationRemainsProjectedObservationWhenConvertedToPerception() {
        val observation = notification()
        val signal = observation.toPerceptionSignal(salience = 0.6)

        assertEquals(PerceptionSource.APP_EVENT, signal.source)
        assertEquals(observation.payload, signal.payload)
        assertTrue("representation:projected" in signal.tags)
        assertTrue("epistemic:observed" in signal.tags)
        assertTrue("information-observation" in signal.tags)
    }

    private fun notification(
        metadata: Map<String, String> = mapOf("package" to "example.app"),
    ) = InformationObservation(
        sourceId = "android-notification-listener",
        sourceResource = "android-notification:example.app:key-1",
        surface = ObservationSurfaceKind.NOTIFICATION,
        observedAt = observedAt,
        sourceTimestamp = observedAt,
        sourceRevision = "revision-1",
        mimeType = "application/vnd.lifeos.android-notification+text",
        payload = "title=Hello\ntext=World",
        realization = RealizationDescriptor(
            representation = RepresentationLevel.PROJECTED,
            epistemicStatus = EpistemicStatus.OBSERVED,
            temporalStatus = TemporalStatus.CURRENT,
            controlStatus = ControlStatus.PASSIVE,
        ),
        authority = ObservationAuthorityClass.PLATFORM_NOTIFICATION,
        privacy = ObservationPrivacyClass.PERSONAL,
        confidence = 1.0,
        tags = setOf("notification", "live-context"),
        metadata = metadata,
    )
}
