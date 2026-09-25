package app.lifeos.core.runtime.android

import app.lifeos.core.field.EvidenceKind
import app.lifeos.core.runtime.life.ControlStatus
import app.lifeos.core.runtime.life.EpistemicStatus
import app.lifeos.core.runtime.life.InformationObservation
import app.lifeos.core.runtime.life.ObservationAuthorityClass
import app.lifeos.core.runtime.life.ObservationPrivacyClass
import app.lifeos.core.runtime.life.ObservationSurfaceKind
import app.lifeos.core.runtime.life.RealizationDescriptor
import app.lifeos.core.runtime.life.RepresentationLevel
import app.lifeos.core.runtime.life.TemporalStatus
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SemanticObservationProjectorsTest {
    @Test
    fun appUiProjectorEmitsObservationEvidenceOnly() {
        val candidate = SemanticAppUiObservationProjector().project(appUi()).single()
        assertEquals(EvidenceKind.OBSERVATION, candidate.kind)
        assertEquals("app.ui.example.bank.current", candidate.stateDimension.value)
        assertEquals("example.bank", candidate.payload.values["package"])
    }

    @Test
    fun notificationProjectorRetainsNotificationSemantics() {
        val candidate = NotificationObservationProjector().project(notification()).single()
        assertEquals(EvidenceKind.MESSAGE_EVENT, candidate.kind)
        assertTrue(candidate.payload.values.getValue("payloadFingerprint").length == 64)
    }

    private fun appUi() = InformationObservation(
        sourceId = "android-accessibility-semantic",
        sourceResource = "android-ui:example.bank:window-1",
        surface = ObservationSurfaceKind.APP_UI,
        observedAt = NOW,
        sourceTimestamp = NOW,
        sourceRevision = "ui-revision",
        mimeType = "application/vnd.lifeos.semantic-ui+text",
        payload = "package=example.bank",
        realization = realization(),
        authority = ObservationAuthorityClass.UI_OBSERVATION,
        privacy = ObservationPrivacyClass.SENSITIVE,
        metadata = mapOf(
            "package" to "example.bank",
            "windowRevision" to "window-1",
            "snapshotFingerprint" to "a".repeat(64),
        ),
    )

    private fun notification() = InformationObservation(
        sourceId = "android-notification-listener",
        sourceResource = "android-notification:example.chat:key",
        surface = ObservationSurfaceKind.NOTIFICATION,
        observedAt = NOW,
        sourceTimestamp = NOW,
        sourceRevision = "notification-revision",
        mimeType = "text/plain",
        payload = "New message",
        realization = realization(),
        authority = ObservationAuthorityClass.PLATFORM_NOTIFICATION,
        privacy = ObservationPrivacyClass.PERSONAL,
    )

    private fun realization() = RealizationDescriptor(
        representation = RepresentationLevel.PROJECTED,
        epistemicStatus = EpistemicStatus.OBSERVED,
        temporalStatus = TemporalStatus.CURRENT,
        controlStatus = ControlStatus.PASSIVE,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-25T05:00:00Z")
    }
}
