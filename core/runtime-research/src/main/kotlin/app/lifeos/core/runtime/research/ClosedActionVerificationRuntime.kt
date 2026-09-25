package app.lifeos.core.runtime.research

import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.runtime.agency.ExternalActionGraphId
import app.lifeos.core.runtime.agency.ExternalActionObservationExpectation
import app.lifeos.core.runtime.agency.ExternalActionObservationReconciler
import app.lifeos.core.runtime.agency.ExternalActionOutcomeState
import app.lifeos.core.runtime.agency.ExternalActionReconciliation
import app.lifeos.core.runtime.agency.ExternalActionReobservationBridge
import app.lifeos.core.runtime.agency.ExternalActionReobservationDecision
import app.lifeos.core.runtime.agency.ExternalActionVerificationGapResolver
import app.lifeos.core.runtime.life.InformationObservation
import app.lifeos.core.runtime.world.WorldGap

data class ClosedActionVerificationResult(
    val sourceObservationId: String,
    val reconciliation: ExternalActionReconciliation?,
    val verificationGap: WorldGap.Verification?,
    val verifiedTerminal: Boolean,
) {
    init {
        if (verifiedTerminal) {
            require(reconciliation != null)
            require(
                reconciliation.state == ExternalActionOutcomeState.CONFIRMED ||
                    reconciliation.state == ExternalActionOutcomeState.CONTRADICTED
            )
            require(verificationGap == null)
        }
    }

    val successAuthority: Boolean
        get() = false

    val effectAuthority: Boolean
        get() = false
}

class ClosedActionVerificationRuntime {
    fun evaluate(
        domainId: FieldDomainId,
        graphId: ExternalActionGraphId,
        expectation: ExternalActionObservationExpectation,
        observation: InformationObservation,
        fieldFingerprints: Map<String, String>,
    ): ClosedActionVerificationResult =
        when (
            val decision = ExternalActionReobservationBridge.fromObservation(
                observation = observation,
                fieldFingerprints = fieldFingerprints,
            )
        ) {
            is ExternalActionReobservationDecision.Insufficient -> {
                val gap = WorldGap.Verification(
                    domain = domainId,
                    actionGraphId = graphId.value,
                    expectedStateContract =
                        "external-action-expectation:" + expectation.fingerprint(),
                    missingObservationContract =
                        "authorized-actual-observation:" + decision.sourceObservationId,
                    reason = decision.reasonCode,
                )
                ClosedActionVerificationResult(
                    sourceObservationId = decision.sourceObservationId,
                    reconciliation = null,
                    verificationGap = gap,
                    verifiedTerminal = false,
                )
            }

            is ExternalActionReobservationDecision.Eligible -> {
                val reconciliation = ExternalActionObservationReconciler.reconcile(
                    expectation = expectation,
                    observation = decision.node,
                )
                val gap = ExternalActionVerificationGapResolver.resolve(
                    domainId = domainId,
                    graphId = graphId,
                    expectation = expectation,
                    reconciliation = reconciliation,
                )
                ClosedActionVerificationResult(
                    sourceObservationId = decision.sourceObservationId,
                    reconciliation = reconciliation,
                    verificationGap = gap,
                    verifiedTerminal =
                        gap == null &&
                            (
                                reconciliation.state == ExternalActionOutcomeState.CONFIRMED ||
                                    reconciliation.state == ExternalActionOutcomeState.CONTRADICTED
                            ),
                )
            }
        }
}
