package app.lifeos.core.runtime.convergence

import app.lifeos.core.field.StableFieldIds

class ConvergenceDecisionEngine(
    private val policy: ConvergenceDecisionPolicy = ConvergenceDecisionPolicy(),
    private val actionGate: ConvergenceActionGate = ConvergenceActionGate(policy),
    private val gapAnalyzer: ConvergenceEvidenceGapAnalyzer = ConvergenceEvidenceGapAnalyzer(policy),
    private val escalationRouter: ConvergenceEscalationRouter = ConvergenceEscalationRouter(),
) {
    fun decide(request: ConvergenceDecisionRequest): ConvergenceDecision {
        val sourceFingerprint = request.sourceFingerprint()
        val gate = actionGate.evaluate(request)

        if (gate.actionable) {
            return decision(
                state = ConvergenceDecisionState.ACTIONABLE,
                selectedHypothesisIds = gate.selectedHypothesisIds,
                candidates = gate.candidates,
                evidenceRequests = emptyList(),
                capabilityGaps = emptyList(),
                escalation = null,
                reasons = gate.reasons,
                sourceFingerprint = sourceFingerprint,
            )
        }

        val evidenceRequests = gapAnalyzer.analyze(request, gate)
        val escalation = escalationRouter.route(request, evidenceRequests)
        val hasCapabilityGap = request.capabilityGaps.isNotEmpty()
        val state = when {
            hasCapabilityGap -> ConvergenceDecisionState.CAPABILITY_REQUIRED
            gate.conflicted -> ConvergenceDecisionState.CONFLICTED
            evidenceRequests.isNotEmpty() -> ConvergenceDecisionState.EVIDENCE_REQUIRED
            else -> ConvergenceDecisionState.UNRESOLVED
        }
        val reasons = buildList {
            addAll(gate.reasons)
            evidenceRequests.forEach { add("evidence-request:${it.kind.name}:${it.id.value}") }
            request.capabilityGaps
                .sortedBy(::capabilityGapFingerprint)
                .forEach { add("capability-required:${it.requirement.capabilityId.value}:${it.type.name}") }
            escalation?.let { add("escalation:${it.target.name}:${it.fingerprint()}") }
            if (isEmpty()) add("convergence-unresolved")
        }.distinct().sorted()

        return decision(
            state = state,
            selectedHypothesisIds = emptyList(),
            candidates = gate.candidates,
            evidenceRequests = evidenceRequests,
            capabilityGaps = request.capabilityGaps.sortedBy(::capabilityGapFingerprint),
            escalation = escalation,
            reasons = reasons,
            sourceFingerprint = sourceFingerprint,
        )
    }

    private fun decision(
        state: ConvergenceDecisionState,
        selectedHypothesisIds: List<app.lifeos.core.field.HypothesisId>,
        candidates: List<ConvergenceCandidateAssessment>,
        evidenceRequests: List<ConvergenceEvidenceRequest>,
        capabilityGaps: List<app.lifeos.core.runtime.capability.CapabilityGap>,
        escalation: ConvergenceEscalationRequest?,
        reasons: List<String>,
        sourceFingerprint: String,
    ): ConvergenceDecision {
        val selected = selectedHypothesisIds.distinct().sortedBy { it.value }
        val orderedCandidates = candidates.sortedWith(
            compareBy<ConvergenceCandidateAssessment> { it.domainId.value }
                .thenByDescending { it.totalScore }
                .thenBy { it.hypothesisId.value }
        )
        val orderedRequests = evidenceRequests.distinctBy { it.id }.sortedBy { it.id.value }
        val orderedGaps = capabilityGaps.distinctBy(::capabilityGapFingerprint).sortedBy(::capabilityGapFingerprint)
        val orderedReasons = reasons.distinct().sorted()
        val id = ConvergenceDecisionId(
            "convergence-decision:${StableFieldIds.fingerprint(
                "convergence-decision/v1",
                policy.fingerprint(),
                sourceFingerprint,
                state.name,
                escalation?.fingerprint().orEmpty(),
                *selected.map { "selected:${it.value}" }.toTypedArray(),
                *orderedCandidates.map { candidateFingerprint(it) }.toTypedArray(),
                *orderedRequests.map { "request:${it.id.value}" }.toTypedArray(),
                *orderedGaps.map(::capabilityGapFingerprint).toTypedArray(),
                *orderedReasons.map { "reason:$it" }.toTypedArray(),
            )}"
        )
        return ConvergenceDecision(
            id = id,
            state = state,
            selectedHypothesisIds = selected,
            candidates = orderedCandidates,
            evidenceRequests = orderedRequests,
            capabilityGaps = orderedGaps,
            escalation = escalation,
            reasons = orderedReasons,
            sourceFingerprint = sourceFingerprint,
        )
    }

    private fun candidateFingerprint(candidate: ConvergenceCandidateAssessment): String =
        StableFieldIds.fingerprint(
            "convergence-candidate-assessment/v1",
            candidate.domainId.value,
            candidate.hypothesisId.value,
            java.lang.Double.toHexString(candidate.totalScore),
            java.lang.Double.toHexString(candidate.evidenceScore),
            java.lang.Double.toHexString(candidate.contradiction),
            java.lang.Double.toHexString(candidate.marginToRunnerUp),
            java.lang.Double.toHexString(candidate.conflictSeverity),
            candidate.freshSupportingEvidence.toString(),
            java.lang.Double.toHexString(candidate.confidenceBand.lower),
            java.lang.Double.toHexString(candidate.confidenceBand.point),
            java.lang.Double.toHexString(candidate.confidenceBand.upper),
        )
}
