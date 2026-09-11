package app.lifeos.core.runtime.convergence

import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.HypothesisId
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.capability.CapabilityGap
import java.time.Duration

@JvmInline
value class EvidenceRequestId(val value: String) {
    init { require(value.isNotBlank()) { "Evidence request id must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class ConvergenceDecisionId(val value: String) {
    init { require(value.isNotBlank()) { "Convergence decision id must not be blank" } }
    override fun toString(): String = value
}

enum class ConvergenceDecisionState {
    ACTIONABLE,
    UNRESOLVED,
    CONFLICTED,
    EVIDENCE_REQUIRED,
    CAPABILITY_REQUIRED,
}

enum class ConvergenceEvidenceGapKind {
    INSUFFICIENT_SUPPORT,
    INSUFFICIENT_MARGIN,
    CONTRADICTION,
    STALE_EVIDENCE,
    MISSING_EVIDENCE,
    DOMAIN_UNRESOLVED,
}

enum class ConvergenceEscalationTarget {
    DEEP_SEARCH,
    TOOL_WORKSHOP,
}

data class ConvergenceDecisionPolicy(
    val minTotalScore: Double = 0.70,
    val minEvidenceScore: Double = 0.55,
    val minWinnerMargin: Double = 0.08,
    val maxContradiction: Double = 0.25,
    val maxConflictSeverity: Double = 0.35,
    val maxEvidenceAge: Duration = Duration.ofDays(365),
    val minFreshSupportingEvidence: Int = 1,
    val confidenceBandHalfWidth: Double = 0.05,
) {
    init {
        listOf(
            minTotalScore,
            minEvidenceScore,
            minWinnerMargin,
            maxContradiction,
            maxConflictSeverity,
            confidenceBandHalfWidth,
        ).forEach { value -> require(value.isFinite() && value in 0.0..1.0) }
        require(!maxEvidenceAge.isNegative && !maxEvidenceAge.isZero) {
            "Maximum evidence age must be positive"
        }
        require(minFreshSupportingEvidence in 1..64)
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "convergence-decision-policy/v1",
        java.lang.Double.toHexString(minTotalScore),
        java.lang.Double.toHexString(minEvidenceScore),
        java.lang.Double.toHexString(minWinnerMargin),
        java.lang.Double.toHexString(maxContradiction),
        java.lang.Double.toHexString(maxConflictSeverity),
        maxEvidenceAge.seconds.toString(),
        maxEvidenceAge.nano.toString(),
        minFreshSupportingEvidence.toString(),
        java.lang.Double.toHexString(confidenceBandHalfWidth),
    )
}

data class ConvergenceConfidenceBand(
    val lower: Double,
    val point: Double,
    val upper: Double,
) {
    init {
        require(lower in 0.0..1.0 && point in 0.0..1.0 && upper in 0.0..1.0)
        require(lower <= point && point <= upper)
    }
}

data class ConvergenceCandidateAssessment(
    val domainId: FieldDomainId,
    val hypothesisId: HypothesisId,
    val totalScore: Double,
    val evidenceScore: Double,
    val contradiction: Double,
    val marginToRunnerUp: Double,
    val conflictSeverity: Double,
    val freshSupportingEvidence: Int,
    val confidenceBand: ConvergenceConfidenceBand,
) {
    init {
        require(totalScore in 0.0..1.0)
        require(evidenceScore.isFinite())
        require(contradiction.isFinite() && contradiction >= 0.0)
        require(marginToRunnerUp.isFinite() && marginToRunnerUp >= 0.0)
        require(conflictSeverity in 0.0..1.0)
        require(freshSupportingEvidence >= 0)
    }
}

data class ConvergenceEvidenceRequest(
    val id: EvidenceRequestId,
    val kind: ConvergenceEvidenceGapKind,
    val domainId: FieldDomainId,
    val hypothesisIds: List<HypothesisId>,
    val semanticKey: String,
    val reason: String,
    val sourceFingerprint: String,
) {
    init {
        require(hypothesisIds.isNotEmpty())
        require(hypothesisIds.distinct().size == hypothesisIds.size)
        require(semanticKey.isNotBlank())
        require(reason.isNotBlank())
        require(sourceFingerprint.isNotBlank())
    }
}

data class ConvergenceEscalationRequest(
    val target: ConvergenceEscalationTarget,
    val reason: String,
    val hypothesisIds: List<HypothesisId>,
    val evidenceRequestIds: List<EvidenceRequestId> = emptyList(),
    val capabilityIds: List<String> = emptyList(),
    val sourceFingerprint: String,
) {
    init {
        require(reason.isNotBlank())
        require(hypothesisIds.distinct().size == hypothesisIds.size)
        require(evidenceRequestIds.distinct().size == evidenceRequestIds.size)
        require(capabilityIds.none { it.isBlank() })
        require(capabilityIds.distinct().size == capabilityIds.size)
        require(sourceFingerprint.isNotBlank())
        when (target) {
            ConvergenceEscalationTarget.DEEP_SEARCH -> require(evidenceRequestIds.isNotEmpty())
            ConvergenceEscalationTarget.TOOL_WORKSHOP -> require(capabilityIds.isNotEmpty())
        }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "convergence-escalation/v1",
        target.name,
        reason,
        sourceFingerprint,
        *hypothesisIds.map { "hypothesis:${it.value}" }.sorted().toTypedArray(),
        *evidenceRequestIds.map { "evidence-request:${it.value}" }.sorted().toTypedArray(),
        *capabilityIds.map { "capability:$it" }.sorted().toTypedArray(),
    )
}

data class ConvergenceDecisionRequest(
    val source: CrossDomainConvergenceRequest,
    val convergence: CrossDomainConvergenceResult,
    val capabilityGaps: List<CapabilityGap> = emptyList(),
    val workingSetFingerprint: String? = null,
) {
    init {
        require(source.id == convergence.requestId) {
            "Decision source request must match convergence result"
        }
        require(workingSetFingerprint == null || workingSetFingerprint.isNotBlank())
    }

    fun sourceFingerprint(): String = StableFieldIds.fingerprint(
        "convergence-decision-source/v1",
        source.id,
        convergence.status.name,
        workingSetFingerprint.orEmpty(),
        *convergence.domainResults.sortedBy { it.state.domainId.value }.flatMap { result ->
            listOf(
                "domain:${result.state.domainId.value}",
                "snapshot:${result.snapshot.id.value}",
                "snapshot-fingerprint:${result.snapshot.contentFingerprint()}",
                "status:${result.status.name}",
            )
        }.toTypedArray(),
        *capabilityGaps.map(::capabilityGapFingerprint).sorted().toTypedArray(),
    )
}

data class ConvergenceDecision(
    val id: ConvergenceDecisionId,
    val state: ConvergenceDecisionState,
    val selectedHypothesisId: HypothesisId?,
    val candidates: List<ConvergenceCandidateAssessment>,
    val evidenceRequests: List<ConvergenceEvidenceRequest>,
    val capabilityGaps: List<CapabilityGap>,
    val escalation: ConvergenceEscalationRequest?,
    val reasons: List<String>,
    val sourceFingerprint: String,
) {
    init {
        require(candidates.distinctBy { it.domainId to it.hypothesisId }.size == candidates.size)
        require(evidenceRequests.distinctBy { it.id }.size == evidenceRequests.size)
        require(reasons.isNotEmpty() && reasons.none { it.isBlank() })
        require(sourceFingerprint.isNotBlank())
        if (state == ConvergenceDecisionState.ACTIONABLE) {
            require(selectedHypothesisId != null)
            require(evidenceRequests.isEmpty())
            require(capabilityGaps.isEmpty())
            require(escalation == null)
        } else {
            require(selectedHypothesisId == null) {
                "Non-actionable convergence decision cannot select a hypothesis"
            }
        }
    }
}

internal fun capabilityGapFingerprint(gap: CapabilityGap): String = StableFieldIds.fingerprint(
    "convergence-capability-gap/v1",
    gap.requirement.capabilityId.value,
    gap.requirement.severity.name,
    gap.type.name,
    *gap.requirement.requiredInputs.map { "input:$it" }.sorted().toTypedArray(),
    *gap.requirement.requiredOutputs.map { "output:$it" }.sorted().toTypedArray(),
    *gap.candidateProviderIds.map { "candidate:$it" }.sorted().toTypedArray(),
)
