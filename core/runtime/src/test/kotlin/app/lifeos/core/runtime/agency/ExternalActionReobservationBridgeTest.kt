package app.lifeos.core.runtime.agency

import app.lifeos.core.field.FieldDomainId
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ExternalActionReobservationBridgeTest {
    private val at = Instant.parse("2026-09-24T12:00:00Z")

    @Test
    fun authorizedActualProviderObservationBecomesReconciliationCandidate() {
        val observation = observation(
            representation = RepresentationLevel.ACTUAL,
            authority = ObservationAuthorityClass.AUTHENTICATED_API,
            grant = "owner-observation-grant:test",
        )

        val decision = ExternalActionReobservationBridge.fromObservation(
            observation,
            mapOf("status" to "b".repeat(64)),
        )

        val eligible = assertIs<ExternalActionReobservationDecision.Eligible>(decision)
        assertEquals(observation.sourceObservationFingerprint, eligible.node.observationFingerprint)
        assertEquals(observation.sourceResource, eligible.node.resourceIdentity)
        assertEquals("b".repeat(64), eligible.node.fieldFingerprints["status"])
    }

    @Test
    fun projectedOrUnauthorizedObservationCannotCloseActionVerification() {
        val projected = ExternalActionReobservationBridge.fromObservation(
            observation(
                representation = RepresentationLevel.PROJECTED,
                authority = ObservationAuthorityClass.PLATFORM_NOTIFICATION,
                grant = "owner-observation-grant:test",
            ),
            mapOf("status" to "b".repeat(64)),
        )
        val unauthorized = ExternalActionReobservationBridge.fromObservation(
            observation(
                representation = RepresentationLevel.ACTUAL,
                authority = ObservationAuthorityClass.AUTHENTICATED_API,
                grant = null,
            ),
            mapOf("status" to "b".repeat(64)),
        )

        assertEquals(
            "observation-not-actual",
            assertIs<ExternalActionReobservationDecision.Insufficient>(projected).reasonCode,
        )
        assertEquals(
            "owner-observation-grant-missing",
            assertIs<ExternalActionReobservationDecision.Insufficient>(unauthorized).reasonCode,
        )
    }

    @Test
    fun unresolvedReconciliationBecomesVerificationGapButVerifiedTerminalStatesDoNot() {
        val graphId = ExternalActionGraphId(
            ExternalActionGraphId.PREFIX + "a".repeat(64)
        )
        val expectation = ExternalActionObservationExpectation(
            resourceIdentity = "bank://account/current",
            exposedAt = at,
            horizon = Duration.ofMinutes(10),
            expectedFieldFingerprints = mapOf("status" to "b".repeat(64)),
        )
        val domain = FieldDomainId("finance")

        val unknown = ExternalActionVerificationGapResolver.resolve(
            domain,
            graphId,
            expectation,
            ExternalActionReconciliation(
                ExternalActionOutcomeState.UNKNOWN,
                null,
                "no-compatible-observation",
            ),
        )
        assertNotNull(unknown)
        assertEquals(graphId.value, unknown.actionGraphId)

        assertNull(
            ExternalActionVerificationGapResolver.resolve(
                domain,
                graphId,
                expectation,
                ExternalActionReconciliation(
                    ExternalActionOutcomeState.CONFIRMED,
                    null,
                    "all-expected-fields-match",
                ),
            )
        )
        assertNull(
            ExternalActionVerificationGapResolver.resolve(
                domain,
                graphId,
                expectation,
                ExternalActionReconciliation(
                    ExternalActionOutcomeState.CONTRADICTED,
                    null,
                    "all-comparable-fields-contradict",
                ),
            )
        )
    }

    private fun observation(
        representation: RepresentationLevel,
        authority: ObservationAuthorityClass,
        grant: String?,
    ): InformationObservation =
        InformationObservation(
            sourceId = "bank-api",
            sourceResource = "bank://account/current",
            surface = ObservationSurfaceKind.API,
            observedAt = at.plusSeconds(30),
            sourceTimestamp = at.plusSeconds(30),
            sourceRevision = "revision-1",
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
            observationGrantId = grant,
            confidence = 1.0,
        )
}
