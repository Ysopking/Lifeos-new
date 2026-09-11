package app.lifeos.core.runtime.convergence

import app.lifeos.core.field.EvidenceRelationType
import app.lifeos.core.field.FieldHypothesis
import app.lifeos.core.field.HypothesisId
import app.lifeos.core.field.StableFieldIds
import java.time.Duration

/** Deterministically turns demonstrated underdetermination into explicit evidence requests. */
class ConvergenceEvidenceGapAnalyzer(
    private val policy: ConvergenceDecisionPolicy = ConvergenceDecisionPolicy(),
) {
    fun analyze(
        request: ConvergenceDecisionRequest,
        gate: ConvergenceActionGateResult,
    ): List<ConvergenceEvidenceRequest> {
        val sourceFingerprint = request.sourceFingerprint()
        val sourceByDomain = request.source.domains.associateBy { it.request.domainId }
        val assessments = gate.candidates.associateBy { it.domainId to it.hypothesisId }
        val gaps = mutableListOf<ConvergenceEvidenceRequest>()

        request.convergence.domainResults
            .sortedBy { it.state.domainId.value }
            .forEach { result ->
                val source = sourceByDomain[result.state.domainId]?.request ?: return@forEach
                val ordered = result.hypotheses.sortedWith(
                    compareByDescending<FieldHypothesis> { it.score.total }.thenBy { it.id.value }
                )
                val top = ordered.firstOrNull() ?: return@forEach
                val runnerUp = ordered.getOrNull(1)
                val topAssessment = assessments[result.state.domainId to top.id] ?: return@forEach
                val evidenceById = source.evidence.associateBy { it.id }
                val supportEvidence = top.evidenceLinks
                    .filter { it.relation != EvidenceRelationType.CONTRADICTS }
                    .mapNotNull { evidenceById[it.evidenceId] }
                val freshSupport = supportEvidence.filter { evidence ->
                    val age = Duration.between(evidence.observedAt, source.context.temporal.queryTime)
                    !age.isNegative && age <= policy.maxEvidenceAge && evidence.validity.contains(source.context.temporal.queryTime)
                }

                if (supportEvidence.isEmpty()) {
                    gaps += gap(
                        kind = ConvergenceEvidenceGapKind.MISSING_EVIDENCE,
                        domainId = result.state.domainId,
                        hypothesisIds = listOf(top.id),
                        semanticKey = top.semanticKey,
                        reason = "hypothesis-has-no-positive-evidence",
                        sourceFingerprint = sourceFingerprint,
                    )
                } else if (freshSupport.size < policy.minFreshSupportingEvidence) {
                    gaps += gap(
                        kind = ConvergenceEvidenceGapKind.STALE_EVIDENCE,
                        domainId = result.state.domainId,
                        hypothesisIds = listOf(top.id),
                        semanticKey = top.semanticKey,
                        reason = "fresh-support-count:${freshSupport.size}:required:${policy.minFreshSupportingEvidence}",
                        sourceFingerprint = sourceFingerprint,
                    )
                }

                if (top.score.total < policy.minTotalScore || top.score.evidence < policy.minEvidenceScore) {
                    gaps += gap(
                        kind = ConvergenceEvidenceGapKind.INSUFFICIENT_SUPPORT,
                        domainId = result.state.domainId,
                        hypothesisIds = listOf(top.id),
                        semanticKey = top.semanticKey,
                        reason = "support-below-action-threshold",
                        sourceFingerprint = sourceFingerprint,
                    )
                }

                if (runnerUp != null && topAssessment.marginToRunnerUp < policy.minWinnerMargin) {
                    gaps += gap(
                        kind = ConvergenceEvidenceGapKind.INSUFFICIENT_MARGIN,
                        domainId = result.state.domainId,
                        hypothesisIds = listOf(top.id, runnerUp.id).sortedBy { it.value },
                        semanticKey = listOf(top.semanticKey, runnerUp.semanticKey).sorted().joinToString("|"),
                        reason = "competing-hypotheses-within-margin:${java.lang.Double.toHexString(topAssessment.marginToRunnerUp)}",
                        sourceFingerprint = sourceFingerprint,
                    )
                }

                if (top.score.contradiction > policy.maxContradiction || topAssessment.conflictSeverity > policy.maxConflictSeverity) {
                    gaps += gap(
                        kind = ConvergenceEvidenceGapKind.CONTRADICTION,
                        domainId = result.state.domainId,
                        hypothesisIds = conflictHypotheses(top, ordered),
                        semanticKey = top.semanticKey,
                        reason = "contradiction-or-conflict-exceeds-action-threshold",
                        sourceFingerprint = sourceFingerprint,
                    )
                }

                if (result.status != app.lifeos.core.field.ConvergenceStatus.CONVERGED) {
                    gaps += gap(
                        kind = ConvergenceEvidenceGapKind.DOMAIN_UNRESOLVED,
                        domainId = result.state.domainId,
                        hypothesisIds = listOf(top.id),
                        semanticKey = top.semanticKey,
                        reason = "domain-convergence-status:${result.status.name}",
                        sourceFingerprint = sourceFingerprint,
                    )
                }
            }

        return gaps
            .distinctBy { it.id }
            .sortedWith(compareBy({ it.domainId.value }, { it.kind.name }, { it.id.value }))
    }

    private fun conflictHypotheses(
        top: FieldHypothesis,
        ordered: List<FieldHypothesis>,
    ): List<HypothesisId> {
        val explicit = top.conflicts.map { it.competingHypothesisId }.toSet()
        return (listOf(top.id) + ordered.filter { it.id in explicit }.map { it.id })
            .distinct()
            .sortedBy { it.value }
    }

    private fun gap(
        kind: ConvergenceEvidenceGapKind,
        domainId: app.lifeos.core.field.FieldDomainId,
        hypothesisIds: List<HypothesisId>,
        semanticKey: String,
        reason: String,
        sourceFingerprint: String,
    ): ConvergenceEvidenceRequest {
        val orderedIds = hypothesisIds.distinct().sortedBy { it.value }
        val id = EvidenceRequestId(
            "evidence-request:${StableFieldIds.fingerprint(
                "convergence-evidence-request/v1",
                kind.name,
                domainId.value,
                semanticKey,
                reason,
                sourceFingerprint,
                *orderedIds.map { it.value }.toTypedArray(),
            )}"
        )
        return ConvergenceEvidenceRequest(
            id = id,
            kind = kind,
            domainId = domainId,
            hypothesisIds = orderedIds,
            semanticKey = semanticKey,
            reason = reason,
            sourceFingerprint = sourceFingerprint,
        )
    }
}
