package app.lifeos.core.runtime.life

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectionClassificationEngineTest {
    private val engine = ProjectionClassificationEngine()

    @Test
    fun notificationCannotBecomeActualFromAdapterClaimAlone() {
        val result = engine.classify(
            observation(surface = ObservationSurfaceKind.NOTIFICATION),
            context = ProjectionClassificationContext(
                directStateRead = true,
                sourceStateClosedForContract = true,
            ),
        )

        assertEquals(RepresentationLevel.PROJECTED, result.descriptor.representation)
        assertTrue("notification-is-projection" in result.reasons)
    }

    @Test
    fun authoritativeClosedProviderReadCanBeActual() {
        val result = engine.classify(
            observation(
                surface = ObservationSurfaceKind.API,
                authority = ObservationAuthorityClass.AUTHORITATIVE_PROVIDER,
            ),
            context = ProjectionClassificationContext(
                directStateRead = true,
                sourceStateClosedForContract = true,
            ),
        )

        assertEquals(RepresentationLevel.ACTUAL, result.descriptor.representation)
    }

    @Test
    fun inferenceNeverBecomesOwnerConfirmation() {
        val result = engine.classify(
            observation(surface = ObservationSurfaceKind.APP_USAGE),
            context = ProjectionClassificationContext(inferred = true),
        )

        assertEquals(EpistemicStatus.INFERRED, result.descriptor.epistemicStatus)
        assertEquals(RepresentationLevel.PROJECTED, result.descriptor.representation)
    }

    private fun observation(
        surface: ObservationSurfaceKind,
        authority: ObservationAuthorityClass =
            ObservationAuthorityClass.PLATFORM_NOTIFICATION,
    ) = InformationObservation(
        sourceId = "sensor",
        sourceResource = "resource:1",
        surface = surface,
        observedAt = Instant.parse("2026-09-24T12:00:00Z"),
        mimeType = "text/plain",
        payload = "value",
        realization = RealizationDescriptor(
            representation = RepresentationLevel.PROJECTED,
            epistemicStatus = EpistemicStatus.OBSERVED,
            temporalStatus = TemporalStatus.CURRENT,
            controlStatus = ControlStatus.PASSIVE,
        ),
        authority = authority,
        privacy = ObservationPrivacyClass.PERSONAL,
    )
}
