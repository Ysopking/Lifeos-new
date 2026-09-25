package app.lifeos.core.runtime.research

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.agency.ExternalActionGraphId
import app.lifeos.core.runtime.agency.ExternalActionObservationExpectation
import app.lifeos.core.runtime.agency.ExternalActionOutcomeState

/**
 * B494 turns only a closed B493 verification into next-cycle learning input.
 *
 * This signal is evidence for learning, not causal proof, policy authority, or execution authority.
 */
data class VerifiedOutcomeLearningSignal private constructor(
    val graphId: String,
    val expectationFingerprint: String,
    val sourceObservationId: String,
    val outcomeState: ExternalActionOutcomeState,
    val reasonCode: String,
    val fingerprint: String,
) {
    init {
        require(graphId.startsWith(ExternalActionGraphId.PREFIX))
        require(expectationFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(sourceObservationId.isNotBlank())
        require(
            outcomeState == ExternalActionOutcomeState.CONFIRMED ||
                outcomeState == ExternalActionOutcomeState.CONTRADICTED
        )
        require(reasonCode.isNotBlank())
        require(
            fingerprint == StableFieldIds.fingerprint(
                "verified-outcome-learning-signal/v1",
                graphId,
                expectationFingerprint,
                sourceObservationId,
                outcomeState.name,
                reasonCode,
            )
        )
    }

    val learningEligible: Boolean
        get() = true

    val causalAuthority: Boolean
        get() = false

    val policyAuthority: Boolean
        get() = false

    val executionAuthority: Boolean
        get() = false

    companion object {
        fun from(
            graphId: ExternalActionGraphId,
            expectation: ExternalActionObservationExpectation,
            verification: ClosedActionVerificationResult,
        ): VerifiedOutcomeLearningSignal? {
            if (!verification.verifiedTerminal) return null
            val reconciliation = requireNotNull(verification.reconciliation)
            require(verification.verificationGap == null)
            require(
                reconciliation.state == ExternalActionOutcomeState.CONFIRMED ||
                    reconciliation.state == ExternalActionOutcomeState.CONTRADICTED
            )

            val expectationFingerprint = expectation.fingerprint()
            val fingerprint = StableFieldIds.fingerprint(
                "verified-outcome-learning-signal/v1",
                graphId.value,
                expectationFingerprint,
                verification.sourceObservationId,
                reconciliation.state.name,
                reconciliation.reasonCode,
            )
            return VerifiedOutcomeLearningSignal(
                graphId = graphId.value,
                expectationFingerprint = expectationFingerprint,
                sourceObservationId = verification.sourceObservationId,
                outcomeState = reconciliation.state,
                reasonCode = reconciliation.reasonCode,
                fingerprint = fingerprint,
            )
        }
    }
}
