package app.lifeos.core.runtime.research

import app.lifeos.core.runtime.agency.ExternalActionGraphId
import app.lifeos.core.runtime.agency.ExternalActionObservationExpectation
import app.lifeos.core.runtime.agency.ExternalActionOutcomeState
import app.lifeos.core.runtime.agency.ExternalActionReconciliation
import app.lifeos.core.runtime.learning.LearningEventKind
import app.lifeos.core.runtime.learning.LearningProvenance
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class PersonalContextLearningEventFactoryTest {
    @Test
    fun contextDeltaStaysInferenceAndVerifiedOutcomeIsExplicit() {
        val delta = PersonalContextDelta(
            kind = PersonalContextDeltaKind.OWNER_AGENCY,
            previousFingerprint = "old",
            currentFingerprint = "new",
            contextFingerprint = "context",
            fingerprint = app.lifeos.core.field.StableFieldIds.fingerprint(
                "personal-context-delta/v1",
                PersonalContextDeltaKind.OWNER_AGENCY.name,
                "old",
                "new",
                "context",
            ),
        )
        val contextEvent = PersonalContextLearningEventFactory.contextDelta(
            delta = delta,
            sequence = 1L,
            occurredAt = NOW,
        )

        val signal = requireNotNull(
            VerifiedOutcomeLearningSignal.from(
                graphId = ExternalActionGraphId(
                    ExternalActionGraphId.PREFIX + "c".repeat(64)
                ),
                expectation = ExternalActionObservationExpectation(
                    resourceIdentity = "account:primary",
                    exposedAt = NOW,
                    horizon = Duration.ofMinutes(5),
                    expectedFieldFingerprints = mapOf("state" to "d".repeat(64)),
                ),
                verification = ClosedActionVerificationResult(
                    sourceObservationId = "observation-1",
                    reconciliation = ExternalActionReconciliation(
                        state = ExternalActionOutcomeState.CONFIRMED,
                        observationId = null,
                        reasonCode = "all-expected-fields-match",
                    ),
                    verificationGap = null,
                    verifiedTerminal = true,
                ),
            )
        )
        val outcomeEvent = PersonalContextLearningEventFactory.verifiedOutcome(
            signal = signal,
            sequence = 2L,
            occurredAt = NOW.plusSeconds(1),
        )

        assertEquals(LearningEventKind.CONTEXT_CHANGE, contextEvent.kind)
        assertEquals(LearningProvenance.INFERENCE, contextEvent.provenance)
        assertEquals(LearningEventKind.COGNITIVE_OUTCOME, outcomeEvent.kind)
        assertEquals(LearningProvenance.VERIFIED_OUTCOME, outcomeEvent.provenance)
        assertEquals(signal.fingerprint, outcomeEvent.attributes["learning_signal_fingerprint"])
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-25T08:45:00Z")
    }
}
