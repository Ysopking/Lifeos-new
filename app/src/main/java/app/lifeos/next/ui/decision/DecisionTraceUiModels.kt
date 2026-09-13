package app.lifeos.next.ui.decision

import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.HypothesisId
import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.convergence.ConvergenceDecisionCheckpointId
import app.lifeos.core.runtime.convergence.ConvergenceDecisionId
import app.lifeos.core.runtime.convergence.ConvergenceDecisionState
import app.lifeos.core.runtime.convergence.ConvergenceEscalationTarget
import app.lifeos.core.runtime.convergence.ConvergenceEvidenceGapKind

enum class DecisionReasonCategory {
    SUCCESS,
    CONVERGENCE,
    EVIDENCE,
    CONFIDENCE,
    CONFLICT,
    CAPABILITY,
    ESCALATION,
    OTHER,
}

data class DecisionReasonUiModel(
    val raw: String,
    val category: DecisionReasonCategory,
    val summary: String,
)

data class DecisionCandidateUiModel(
    val domainId: FieldDomainId,
    val hypothesisId: HypothesisId,
    val selected: Boolean,
    val totalScore: Double,
    val evidenceScore: Double,
    val contradiction: Double,
    val marginToRunnerUp: Double,
    val conflictSeverity: Double,
    val freshSupportingEvidence: Int,
    val confidenceLower: Double,
    val confidencePoint: Double,
    val confidenceUpper: Double,
)

data class DecisionEvidenceRequestUiModel(
    val kind: ConvergenceEvidenceGapKind,
    val domainId: FieldDomainId,
    val hypothesisIds: List<HypothesisId>,
    val semanticKey: String,
    val reason: String,
)

data class DecisionCapabilityGapUiModel(
    val capabilityId: String,
    val severity: String,
    val gapType: String,
    val candidateProviderIds: List<String>,
)

data class DecisionEscalationUiModel(
    val target: ConvergenceEscalationTarget,
    val reason: String,
    val hypothesisIds: List<HypothesisId>,
    val evidenceRequestIds: List<String>,
    val capabilityIds: List<String>,
)

data class DecisionSnapshotUiModel(
    val domainId: FieldDomainId,
    val snapshotId: String,
    val contentFingerprint: String,
)

data class DecisionTraceUiModel(
    val checkpointId: ConvergenceDecisionCheckpointId,
    val decisionId: ConvergenceDecisionId,
    val state: ConvergenceDecisionState,
    val headline: String,
    val summary: String,
    val reasons: List<DecisionReasonUiModel>,
    val candidates: List<DecisionCandidateUiModel>,
    val evidenceRequests: List<DecisionEvidenceRequestUiModel>,
    val capabilityGaps: List<DecisionCapabilityGapUiModel>,
    val escalation: DecisionEscalationUiModel?,
    val snapshots: List<DecisionSnapshotUiModel>,
    val selectedHypothesisIds: List<HypothesisId>,
    val sourceRequestId: String,
    val sourceFingerprint: String,
    val policyFingerprint: String,
    val workingSetFingerprint: String?,
)

data class DecisionTraceWorkspaceUiModel(
    val traces: List<DecisionTraceUiModel>,
) {
    companion object {
        fun empty(): DecisionTraceWorkspaceUiModel = DecisionTraceWorkspaceUiModel(emptyList())
    }
}
