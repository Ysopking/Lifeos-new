package app.lifeos.core.runtime.research

import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.runtime.agency.ExternalActionGraphId
import app.lifeos.core.runtime.agency.ExternalActionObservationExpectation
import app.lifeos.core.runtime.agency.ExternalActionOutcomeState
import app.lifeos.core.runtime.life.ControlStatus
import app.lifeos.core.runtime.life.EpistemicStatus
import app.lifeos.core.runtime.life.InformationObservation
import app.lifeos.core.runtime.life.ObservationAuthorityClass
import app.lifeos.core.runtime.life.ObservationPrivacyClass
import app.lifeos.core.runtime.life.ObservationSurfaceKind
import app.lifeos.core.runtime.life.RealizationDescriptor
import app.lifeos.core.runtime.life.RepresentationLevel
import app.lifeos.core.runtime.life.TemporalStatus
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClosedActionVerificationRuntimeTest {
    @Test
    fun authorizedActualProviderObservationCanCloseVerification() {
        val expected = "a".repeat(64)
        val result = ClosedActionVerificationRuntime().evaluate(
            domainId = FieldDomainId("finance"),
            graphId = graphId(),
            expectation = expectation(expected),
            observation = observation(
                grantId = "grant-1",
                representation = RepresentationLevel.ACTUAL,
                authority = ObservationAuthorityClass.PLATFORM_PROVIDER,
            ),
            fieldFingerprints = mapOf("state" to expected),
        )

        assertEquals(ExternalActionOutcomeState.CONFIRMED, result.reconciliation?.state)
        assertTrue(result.verifiedTerminal)
        assertNull(result.verificationGap)
        assertFalse(result.successAuthority)
    }

    @Test
    fun projectedUiObservationCannotCloseVerification() {
        val result = ClosedActionVerificationRuntime().evaluate(
            domainId = FieldDomainId("finance"),
            graphId = graphId(),
            expectation = expectation("a".repeat(64)),
            observation = observation(
                grantId = "grant-1",
                representation = RepresentationLevel.PROJECTED,
                authority = ObservationAuthorityClass.UI_OBSERVATION,
            ),
            fieldFingerprints = mapOf("state" to "a".repeat(64)),
        )

        assertFalse(result.verifiedTerminal)
        assertEquals("observation-not-actual", result.verificationGap?.reason)
        assertNull(result.reconciliation)
    }

    private fun expectation(expected: String) = ExternalActionObservationExpectation(
        resourceIdentity = "account:primary",
        exposedAt = NOW,
        horizon = Duration.ofMinutes(5),
        expectedFieldFingerprints = mapOf("state" to expected),
    )

    private fun graphId() = ExternalActionGraphId(
        ExternalActionGraphId.PREFIX + "b".repeat(64)
    )

    private fun observation(
        grantId: String?,
        representation: RepresentationLevel,
        authority: ObservationAuthorityClass,
    ) = InformationObservation(
        sourceId = "provider",
        sourceResource = "account:primary",
        surface = ObservationSurfaceKind.EXTERNAL_PROVIDER,
        observedAt = NOW.plusSeconds(30),
        sourceTimestamp = NOW.plusSeconds(30),
        sourceRevision = "rev-1",
        mimeType = "application/json",
        payload = "{}",
        realization = RealizationDescriptor(
            representation = representation,
            epistemicStatus = EpistemicStatus.OBSERVED,
            temporalStatus = TemporalStatus.CURRENT,
            controlStatus = ControlStatus.PASSIVE,
        ),
        authority = authority,
        privacy = ObservationPrivacyClass.SENSITIVE,
        observationGrantId = grantId,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-25T07:30:00Z")
    }
}
