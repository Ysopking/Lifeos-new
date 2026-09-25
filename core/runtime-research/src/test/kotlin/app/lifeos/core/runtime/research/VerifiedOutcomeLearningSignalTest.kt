package app.lifeos.core.runtime.research

import app.lifeos.core.runtime.agency.ExternalActionGraphId
import app.lifeos.core.runtime.agency.ExternalActionObservationExpectation
import app.lifeos.core.runtime.agency.ExternalActionOutcomeState
import app.lifeos.core.runtime.agency.ExternalActionReconciliation
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VerifiedOutcomeLearningSignalTest {
    @Test
    fun onlyTerminalVerifiedOutcomeBecomesLearningSignal() {
        val verification = ClosedActionVerificationResult(
            sourceObservationId = "observation-1",
            reconciliation = ExternalActionReconciliation(
                state = ExternalActionOutcomeState.CONFIRMED,
                observationId = null,
                reasonCode = "all-expected-fields-match",
            ),
            verificationGap = null,
            verifiedTerminal = true,
        )

        val signal = requireNotNull(
            VerifiedOutcomeLearningSignal.from(
                graphId = graphId(),
                expectation = expectation(),
                verification = verification,
            )
        )

        assertEquals(ExternalActionOutcomeState.CONFIRMED, signal.outcomeState)
        assertTrue(signal.learningEligible)
        assertFalse(signal.causalAuthority)
        assertFalse(signal.policyAuthority)
        assertFalse(signal.executionAuthority)
    }

    @Test
    fun unresolvedVerificationCannotBecomeLearningSignal() {
        val verification = ClosedActionVerificationResult(
            sourceObservationId = "observation-2",
            reconciliation = null,
            verificationGap = app.lifeos.core.runtime.world.WorldGap.Verification(
                domain = app.lifeos.core.field.FieldDomainId("finance"),
                actionGraphId = graphId().value,
                expectedStateContract = "expected",
                missingObservationContract = "actual",
                reason = "observation-not-actual",
            ),
            verifiedTerminal = false,
        )

        assertNull(
            VerifiedOutcomeLearningSignal.from(
                graphId = graphId(),
                expectation = expectation(),
                verification = verification,
            )
        )
    }

    private fun graphId() = ExternalActionGraphId(
        ExternalActionGraphId.PREFIX + "a".repeat(64)
    )

    private fun expectation() = ExternalActionObservationExpectation(
        resourceIdentity = "account:primary",
        exposedAt = NOW,
        horizon = Duration.ofMinutes(5),
        expectedFieldFingerprints = mapOf("balance" to "b".repeat(64)),
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-25T08:00:00Z")
    }
}
