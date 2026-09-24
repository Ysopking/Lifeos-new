package app.lifeos.core.runtime.agency

import app.lifeos.core.field.FieldDomainId
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ExternalActionVerificationGapResolverTest {
    private val domain = FieldDomainId("finance")
    private val graphId = ExternalActionGraphId(
        ExternalActionGraphId.PREFIX + "a".repeat(64)
    )
    private val expectation = ExternalActionObservationExpectation(
        resourceIdentity = "bank://account/current",
        exposedAt = Instant.parse("2026-09-24T12:00:00Z"),
        horizon = Duration.ofMinutes(10),
        expectedFieldFingerprints = mapOf(
            "status" to "b".repeat(64),
        ),
    )

    @Test
    fun unknownOutcomeBecomesVerificationGap() {
        val gap = ExternalActionVerificationGapResolver.resolve(
            domainId = domain,
            graphId = graphId,
            expectation = expectation,
            reconciliation = ExternalActionReconciliation(
                state = ExternalActionOutcomeState.UNKNOWN,
                observationId = null,
                reasonCode = "no-compatible-observation",
            ),
        )

        val actual = assertNotNull(gap)
        assertEquals(graphId.value, actual.actionGraphId)
        assertEquals("no-compatible-observation", actual.reason)
    }

    @Test
    fun partialOutcomeRemainsVerificationGap() {
        val gap = ExternalActionVerificationGapResolver.resolve(
            domainId = domain,
            graphId = graphId,
            expectation = expectation,
            reconciliation = ExternalActionReconciliation(
                state = ExternalActionOutcomeState.PARTIAL,
                observationId = null,
                reasonCode = "mixed-or-incomplete-observation",
            ),
        )

        assertNotNull(gap)
    }

    @Test
    fun confirmedOrContradictedOutcomeClosesVerificationGap() {
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

    @Test
    fun verificationGapIdentityIsReplayStable() {
        val reconciliation = ExternalActionReconciliation(
            state = ExternalActionOutcomeState.UNKNOWN,
            observationId = null,
            reasonCode = "no-observation",
        )

        val first = ExternalActionVerificationGapResolver.resolve(
            domain,
            graphId,
            expectation,
            reconciliation,
        )
        val second = ExternalActionVerificationGapResolver.resolve(
            domain,
            graphId,
            expectation,
            reconciliation,
        )

        assertEquals(first, second)
        assertEquals(first?.id, second?.id)
    }
}
