package app.lifeos.next.ui.decision

import app.lifeos.core.runtime.convergence.ConvergenceDecisionCheckpoint
import app.lifeos.core.runtime.convergence.ConvergenceDecisionState
import app.lifeos.core.runtime.convergence.ConvergenceEscalationTarget

/** Pure read-only explanation projection over durable convergence decision checkpoints. */
object DecisionTraceProjector {
    fun project(
        checkpoints: Iterable<ConvergenceDecisionCheckpoint>,
    ): DecisionTraceWorkspaceUiModel = DecisionTraceWorkspaceUiModel(
        traces = checkpoints
            .map(::projectCheckpoint)
            .sortedBy { it.checkpointId.value },
    )

    private fun projectCheckpoint(checkpoint: ConvergenceDecisionCheckpoint): DecisionTraceUiModel {
        val decision = checkpoint.decision
        val selected = decision.selectedHypothesisIds.toSet()
        return DecisionTraceUiModel(
            checkpointId = checkpoint.id,
            decisionId = decision.id,
            state = decision.state,
            headline = headline(decision.state),
            summary = summary(decision.state),
            reasons = decision.reasons.map(::projectReason),
            candidates = decision.candidates.map { candidate ->
                DecisionCandidateUiModel(
                    domainId = candidate.domainId,
                    hypothesisId = candidate.hypothesisId,
                    selected = candidate.hypothesisId in selected,
                    totalScore = candidate.totalScore,
                    evidenceScore = candidate.evidenceScore,
                    contradiction = candidate.contradiction,
                    marginToRunnerUp = candidate.marginToRunnerUp,
                    conflictSeverity = candidate.conflictSeverity,
                    freshSupportingEvidence = candidate.freshSupportingEvidence,
                    confidenceLower = candidate.confidenceBand.lower,
                    confidencePoint = candidate.confidenceBand.point,
                    confidenceUpper = candidate.confidenceBand.upper,
                )
            },
            evidenceRequests = decision.evidenceRequests.map { request ->
                DecisionEvidenceRequestUiModel(
                    kind = request.kind,
                    domainId = request.domainId,
                    hypothesisIds = request.hypothesisIds.sortedBy { it.value },
                    semanticKey = request.semanticKey,
                    reason = request.reason,
                )
            },
            capabilityGaps = decision.capabilityGaps.map { gap ->
                DecisionCapabilityGapUiModel(
                    capabilityId = gap.requirement.capabilityId.value,
                    severity = gap.requirement.severity.name,
                    gapType = gap.type.name,
                    candidateProviderIds = gap.candidateProviderIds.sorted(),
                )
            },
            escalation = decision.escalation?.let { escalation ->
                DecisionEscalationUiModel(
                    target = escalation.target,
                    reason = escalation.reason,
                    hypothesisIds = escalation.hypothesisIds.sortedBy { it.value },
                    evidenceRequestIds = escalation.evidenceRequestIds.map { it.value }.sorted(),
                    capabilityIds = escalation.capabilityIds.sorted(),
                )
            },
            snapshots = checkpoint.snapshots
                .sortedBy { it.domainId.value }
                .map { snapshot ->
                    DecisionSnapshotUiModel(
                        domainId = snapshot.domainId,
                        snapshotId = snapshot.snapshotId.value,
                        contentFingerprint = snapshot.contentFingerprint,
                    )
                },
            selectedHypothesisIds = decision.selectedHypothesisIds.sortedBy { it.value },
            sourceRequestId = checkpoint.sourceRequestId,
            sourceFingerprint = checkpoint.sourceFingerprint,
            policyFingerprint = checkpoint.policyFingerprint,
            workingSetFingerprint = checkpoint.workingSetFingerprint,
        )
    }

    private fun headline(state: ConvergenceDecisionState): String = when (state) {
        ConvergenceDecisionState.ACTIONABLE -> "Handlungsfähig"
        ConvergenceDecisionState.EVIDENCE_REQUIRED -> "Mehr Evidenz nötig"
        ConvergenceDecisionState.CAPABILITY_REQUIRED -> "Fähigkeit fehlt"
        ConvergenceDecisionState.CONFLICTED -> "Widersprüchliche Lage"
        ConvergenceDecisionState.UNRESOLVED -> "Noch nicht entschieden"
    }

    private fun summary(state: ConvergenceDecisionState): String = when (state) {
        ConvergenceDecisionState.ACTIONABLE ->
            "Die vorhandene Evidenz erfüllt die Entscheidungsbedingungen."
        ConvergenceDecisionState.EVIDENCE_REQUIRED ->
            "LIFEOS hält die Entscheidung zurück, bis die ausgewiesenen Evidenzlücken geklärt sind."
        ConvergenceDecisionState.CAPABILITY_REQUIRED ->
            "Die Entscheidung kann nicht ausgeführt werden, weil mindestens eine benötigte Fähigkeit fehlt."
        ConvergenceDecisionState.CONFLICTED ->
            "Widersprüche oder Konflikte sind zu stark für eine belastbare Entscheidung."
        ConvergenceDecisionState.UNRESOLVED ->
            "Die vorhandene Lage reicht noch nicht für eine belastbare Entscheidung aus."
    }

    private fun projectReason(raw: String): DecisionReasonUiModel {
        val category = when {
            raw == "all-convergence-action-gates-satisfied" -> DecisionReasonCategory.SUCCESS
            raw.startsWith("evidence-request:") ||
                raw.startsWith("evidence-score-below-threshold:") ||
                raw.startsWith("fresh-support-below-threshold:") -> DecisionReasonCategory.EVIDENCE
            raw.startsWith("total-score-below-threshold:") ||
                raw.startsWith("winner-margin-below-threshold:") ||
                raw.startsWith("confidence-bands-overlap:") -> DecisionReasonCategory.CONFIDENCE
            raw.startsWith("contradiction-above-threshold:") ||
                raw.startsWith("conflict-above-threshold:") -> DecisionReasonCategory.CONFLICT
            raw.startsWith("capability-gap:") || raw.startsWith("capability-required:") ->
                DecisionReasonCategory.CAPABILITY
            raw.startsWith("escalation:") -> DecisionReasonCategory.ESCALATION
            raw.startsWith("cross-domain-not-converged:") ||
                raw.startsWith("domain-not-converged:") ||
                raw.startsWith("convergence-failure:") ||
                raw.startsWith("missing-source-domain:") ||
                raw.startsWith("no-hypothesis:") ||
                raw == "convergence-unresolved" -> DecisionReasonCategory.CONVERGENCE
            else -> DecisionReasonCategory.OTHER
        }
        return DecisionReasonUiModel(
            raw = raw,
            category = category,
            summary = reasonSummary(raw, category),
        )
    }

    private fun reasonSummary(raw: String, category: DecisionReasonCategory): String = when {
        raw == "all-convergence-action-gates-satisfied" ->
            "Alle Bedingungen für eine belastbare Handlung sind erfüllt."
        raw.startsWith("cross-domain-not-converged:") ->
            "Die beteiligten Bereiche haben noch kein gemeinsames Ergebnis erreicht."
        raw.startsWith("domain-not-converged:") ->
            "Mindestens ein Entscheidungsbereich ist noch nicht konvergiert."
        raw.startsWith("convergence-failure:") ->
            "Im Konvergenzlauf wurde ein Fehler erhalten."
        raw.startsWith("missing-source-domain:") ->
            "Für einen Entscheidungsbereich fehlt die zugehörige Quelllage."
        raw.startsWith("no-hypothesis:") ->
            "Für einen Entscheidungsbereich liegt keine bewertbare Hypothese vor."
        raw.startsWith("total-score-below-threshold:") ->
            "Die stärkste Hypothese erreicht die erforderliche Gesamtbewertung nicht."
        raw.startsWith("evidence-score-below-threshold:") ->
            "Die Evidenzstärke reicht für eine Handlung noch nicht aus."
        raw.startsWith("winner-margin-below-threshold:") ->
            "Die führende Hypothese liegt nicht deutlich genug vor der Alternative."
        raw.startsWith("confidence-bands-overlap:") ->
            "Die Unsicherheitsbereiche der führenden Hypothesen überlappen sich."
        raw.startsWith("contradiction-above-threshold:") ->
            "Der Widerspruch gegen die führende Hypothese ist zu hoch."
        raw.startsWith("conflict-above-threshold:") ->
            "Ein erhaltener Konflikt ist zu stark für eine sichere Handlung."
        raw.startsWith("fresh-support-below-threshold:") ->
            "Es gibt nicht genug frische stützende Evidenz."
        raw.startsWith("evidence-request:") ->
            "Zusätzliche Evidenz wurde gezielt angefordert."
        raw.startsWith("capability-gap:") || raw.startsWith("capability-required:") ->
            "Eine benötigte Fähigkeit ist derzeit nicht ausreichend verfügbar."
        raw.startsWith("escalation:${ConvergenceEscalationTarget.DEEP_SEARCH.name}:") ->
            "Die Evidenzlücke wurde an Deep Search eskaliert."
        raw.startsWith("escalation:${ConvergenceEscalationTarget.TOOL_WORKSHOP.name}:") ->
            "Die fehlende Fähigkeit wurde an die Toolwerkstatt eskaliert."
        raw == "convergence-unresolved" ->
            "Die Lage bleibt ohne belastbares Ergebnis."
        category == DecisionReasonCategory.OTHER ->
            "Weitere technische Entscheidungsbegründung liegt vor."
        else -> raw
    }
}
