package app.lifeos.core.runtime.convergence

import app.lifeos.core.field.ConvergenceStatus
import app.lifeos.core.field.EvidenceRelationType
import app.lifeos.core.field.FieldConvergenceResult
import app.lifeos.core.field.FieldEvidence
import app.lifeos.core.field.FieldHypothesis
import app.lifeos.core.field.HypothesisId
import java.time.Duration

data class ConvergenceActionGateResult(
    val actionable: Boolean,
    val conflicted: Boolean,
    val selectedHypothesisIds: List<HypothesisId>,
    val candidates: List<ConvergenceCandidateAssessment>,
    val reasons: List<String>,
) {
    init {
        require(reasons.isNotEmpty())
        if (actionable) require(selectedHypothesisIds.isNotEmpty())
        if (!actionable) require(selectedHypothesisIds.isEmpty())
    }
}

/** Pure V5 policy gate. Stable ids order results but never resolve score ties. */
class ConvergenceActionGate(
    private val policy: ConvergenceDecisionPolicy = ConvergenceDecisionPolicy(),
) {
    fun evaluate(request: ConvergenceDecisionRequest): ConvergenceActionGateResult {
        val sourceByDomain = request.source.domains.associateBy { it.request.domainId }
        val assessments = mutableListOf<ConvergenceCandidateAssessment>()
        val selected = mutableListOf<HypothesisId>()
        val reasons = mutableListOf<String>()
        var conflicted = false
        var allDomainsPass = request.convergence.domainResults.isNotEmpty()

        if (request.convergence.status != CrossDomainConvergenceStatus.CONVERGED) {
            allDomainsPass = false
            reasons += "cross-domain-not-converged:${request.convergence.status.name}"
        }
        if (request.convergence.failures.isNotEmpty()) {
            allDomainsPass = false
            reasons += request.convergence.failures.map { "convergence-failure:$it" }
        }

        request.convergence.domainResults
            .sortedBy { it.state.domainId.value }
            .forEach { result ->
                val source = sourceByDomain[result.state.domainId]?.request
                if (source == null) {
                    allDomainsPass = false
                    reasons += "missing-source-domain:${result.state.domainId.value}"
                    return@forEach
                }
                val ordered = result.hypotheses.sortedWith(
                    compareByDescending<FieldHypothesis> { it.score.total }.thenBy { it.id.value }
                )
                val top = ordered.firstOrNull()
                if (top == null) {
                    allDomainsPass = false
                    reasons += "no-hypothesis:${result.state.domainId.value}"
                    return@forEach
                }
                val runnerUp = ordered.getOrNull(1)
                ordered.forEach { hypothesis ->
                    assessments += assessment(
                        result = result,
                        hypothesis = hypothesis,
                        sourceEvidence = source.evidence,
                        queryTime = source.context.temporal.queryTime,
                        margin = if (hypothesis.id == top.id) {
                            (top.score.total - (runnerUp?.score?.total ?: 0.0)).coerceAtLeast(0.0)
                        } else {
                            0.0
                        },
                    )
                }

                val domainAssessments = assessments.filter { it.domainId == result.state.domainId }
                val topAssessment = domainAssessments.first { it.hypothesisId == top.id }
                val runnerAssessment = runnerUp?.let { runner ->
                    domainAssessments.first { it.hypothesisId == runner.id }
                }
                val confidenceBandsOverlap = runnerAssessment != null &&
                    topAssessment.confidenceBand.lower <= runnerAssessment.confidenceBand.upper
                val domainReasons = buildList {
                    if (result.status != ConvergenceStatus.CONVERGED) add("domain-not-converged:${result.state.domainId.value}:${result.status.name}")
                    if (top.score.total < policy.minTotalScore) add("total-score-below-threshold:${top.id.value}")
                    if (top.score.evidence < policy.minEvidenceScore) add("evidence-score-below-threshold:${top.id.value}")
                    if (runnerUp != null && topAssessment.marginToRunnerUp < policy.minWinnerMargin) {
                        add("winner-margin-below-threshold:${top.id.value}:${runnerUp.id.value}")
                    }
                    if (runnerUp != null && confidenceBandsOverlap) {
                        add("confidence-bands-overlap:${top.id.value}:${runnerUp.id.value}")
                    }
                    if (top.score.contradiction > policy.maxContradiction) add("contradiction-above-threshold:${top.id.value}")
                    if (topAssessment.conflictSeverity > policy.maxConflictSeverity) add("conflict-above-threshold:${top.id.value}")
                    if (topAssessment.freshSupportingEvidence < policy.minFreshSupportingEvidence) {
                        add("fresh-support-below-threshold:${top.id.value}")
                    }
                }
                if (topAssessment.conflictSeverity > policy.maxConflictSeverity || top.score.contradiction > policy.maxContradiction) {
                    conflicted = true
                }
                if (domainReasons.isEmpty()) {
                    selected += top.id
                } else {
                    allDomainsPass = false
                    reasons += domainReasons
                }
            }

        if (request.capabilityGaps.isNotEmpty()) {
            allDomainsPass = false
            reasons += request.capabilityGaps
                .sortedBy(::capabilityGapFingerprint)
                .map { "capability-gap:${it.requirement.capabilityId.value}:${it.type.name}:${it.requirement.severity.name}" }
        }

        val actionable = allDomainsPass && selected.size == request.convergence.domainResults.size
        return ConvergenceActionGateResult(
            actionable = actionable,
            conflicted = conflicted,
            selectedHypothesisIds = if (actionable) selected.distinct().sortedBy { it.value } else emptyList(),
            candidates = assessments.sortedWith(
                compareBy<ConvergenceCandidateAssessment> { it.domainId.value }
                    .thenByDescending { it.totalScore }
                    .thenBy { it.hypothesisId.value }
            ),
            reasons = if (actionable) listOf("all-convergence-action-gates-satisfied") else reasons.distinct().sorted(),
        )
    }

    private fun assessment(
        result: FieldConvergenceResult,
        hypothesis: FieldHypothesis,
        sourceEvidence: List<FieldEvidence>,
        queryTime: java.time.Instant,
        margin: Double,
    ): ConvergenceCandidateAssessment {
        val evidenceById = sourceEvidence.associateBy { it.id }
        val supporting = hypothesis.evidenceLinks
            .filter { it.relation != EvidenceRelationType.CONTRADICTS }
            .mapNotNull { evidenceById[it.evidenceId] }
        val freshCount = supporting.count { evidence ->
            val age = Duration.between(evidence.observedAt, queryTime)
            !age.isNegative && age <= policy.maxEvidenceAge && evidence.validity.contains(queryTime)
        }
        val conflictSeverity = result.conflicts
            .filter { conflict -> conflict.nodeIds.any(hypothesis.nodeIds::contains) }
            .maxOfOrNull { it.severity }
            ?: 0.0
        val uncertainty = (
            policy.confidenceBandHalfWidth +
                hypothesis.score.contradiction.coerceIn(0.0, 1.0) * 0.10 +
                if (freshCount == 0) 0.05 else 0.0
            ).coerceIn(0.0, 0.25)
        return ConvergenceCandidateAssessment(
            domainId = result.state.domainId,
            hypothesisId = hypothesis.id,
            totalScore = hypothesis.score.total,
            evidenceScore = hypothesis.score.evidence,
            contradiction = hypothesis.score.contradiction,
            marginToRunnerUp = margin,
            conflictSeverity = conflictSeverity,
            freshSupportingEvidence = freshCount,
            confidenceBand = ConvergenceConfidenceBand(
                lower = (hypothesis.score.total - uncertainty).coerceIn(0.0, 1.0),
                point = hypothesis.score.total,
                upper = (hypothesis.score.total + uncertainty).coerceIn(0.0, 1.0),
            ),
        )
    }
}
