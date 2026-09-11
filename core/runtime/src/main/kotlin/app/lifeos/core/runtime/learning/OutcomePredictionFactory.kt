package app.lifeos.core.runtime.learning

import app.lifeos.core.runtime.convergence.ConvergenceDecision
import app.lifeos.core.runtime.convergence.ConvergenceDecisionPolicy
import app.lifeos.core.runtime.convergence.ConvergenceDecisionRequest
import app.lifeos.core.runtime.convergence.ConvergenceDecisionState
import java.time.Instant

/**
 * Creates the immutable prediction boundary before productive action execution.
 * No lineage is synthesized: actionable V5 decision, explicit working set, selected providers,
 * and authoritative FieldSnapshot fingerprints are all mandatory.
 */
class OutcomePredictionFactory {
    fun fromActionableDecision(
        actionId: String,
        request: ConvergenceDecisionRequest,
        decision: ConvergenceDecision,
        providerIds: List<String>,
        policy: ConvergenceDecisionPolicy,
        createdAt: Instant,
    ): OutcomePrediction {
        require(actionId.isNotBlank()) { "Productive action id must not be blank" }
        require(decision.state == ConvergenceDecisionState.ACTIONABLE) {
            "Outcome prediction can only precede an ACTIONABLE convergence decision"
        }
        require(decision.sourceFingerprint == request.sourceFingerprint()) {
            "Outcome prediction decision/source lineage mismatch"
        }
        val workingSetFingerprint = requireNotNull(request.workingSetFingerprint) {
            "Productive outcome prediction requires explicit ThoughtGraph working-set lineage"
        }
        require(providerIds.isNotEmpty() && providerIds.none { it.isBlank() }) {
            "Productive outcome prediction requires selected provider identities"
        }
        require(providerIds.distinct().size == providerIds.size) {
            "Productive outcome prediction provider identities must be unique"
        }
        val selected = decision.selectedHypothesisIds.toSet()
        require(selected.isNotEmpty()) { "ACTIONABLE decision must select hypotheses" }
        val selectedCandidates = decision.candidates.filter { it.hypothesisId in selected }
        require(selectedCandidates.map { it.hypothesisId }.toSet() == selected) {
            "Every selected hypothesis requires a confidence assessment"
        }
        val fieldFingerprints = request.convergence.domainResults
            .map { it.snapshot.contentFingerprint() }
            .distinct()
            .sorted()
        require(fieldFingerprints.isNotEmpty()) {
            "Productive outcome prediction requires authoritative field snapshot lineage"
        }

        return OutcomePrediction.create(
            actionId = actionId,
            decisionId = decision.id,
            expectedHypotheses = selectedCandidates.map { candidate ->
                OutcomeExpectedHypothesis(
                    hypothesisId = candidate.hypothesisId,
                    confidenceBand = candidate.confidenceBand,
                )
            },
            providerIds = providerIds,
            fieldSnapshotFingerprints = fieldFingerprints,
            thoughtGraphWorkingSetFingerprint = workingSetFingerprint,
            decisionPolicyFingerprint = policy.fingerprint(),
            createdAt = createdAt,
        )
    }
}
