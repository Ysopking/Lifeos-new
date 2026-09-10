package app.lifeos.core.runtime.evolution

/**
 * Deterministic, side-effect-free evaluator for paired baseline/candidate shadow evidence.
 * It does not execute providers and cannot promote or activate a generated tool.
 */
class ShadowEvaluationEngine(
    private val policy: EvolutionEvaluationPolicy = EvolutionEvaluationPolicy(),
) {
    fun evaluate(
        subject: EvolutionSubject,
        dataset: EvolutionDatasetRef,
        evaluatorId: String,
        cases: List<EvolutionTestCase>,
        observations: List<EvolutionShadowObservation>,
    ): EvolutionEvaluationReport {
        require(evaluatorId.isNotBlank()) { "Evolution evaluator id must not be blank" }
        require(evaluatorId != subject.candidateToolId) {
            "Evolution candidate cannot evaluate itself"
        }
        require(dataset.curatorId != subject.candidateToolId) {
            "Evolution candidate cannot curate its own holdout dataset"
        }
        require(cases.isNotEmpty()) { "Evolution evaluation requires test cases" }
        require(cases.map { it.caseId }.distinct().size == cases.size) {
            "Evolution test case ids must be unique"
        }
        require(cases.map { it.inputFingerprint }.distinct().size == cases.size) {
            "Evolution holdout inputs must be distinct"
        }

        val caseIds = cases.map { it.caseId }.toSet()
        require(observations.all { it.caseId in caseIds }) {
            "Evolution observation references unknown test case"
        }
        require(observations.map { it.side to it.caseId }.distinct().size == observations.size) {
            "Evolution evaluation accepts exactly one observation per side and case"
        }

        val baseline = observations.filter { it.side == EvolutionObservationSide.BASELINE }
        val candidate = observations.filter { it.side == EvolutionObservationSide.CANDIDATE }
        require(baseline.size == cases.size) { "Every test case requires one baseline observation" }
        require(candidate.size == cases.size) { "Every test case requires one candidate observation" }
        require(baseline.all { it.providerId == subject.baselineProviderId }) {
            "Baseline observations belong to another provider"
        }
        require(candidate.all { it.providerId == subject.candidateToolId }) {
            "Candidate observations belong to another provider"
        }
        require(observations.all { it.executionMode == EvolutionExecutionMode.SHADOW }) {
            "Evolution observations must be produced in SHADOW mode"
        }

        val baselineStats = evaluationStats(baseline)
        val candidateStats = evaluationStats(candidate)
        val reasons = mutableListOf<String>()
        val decision = decide(
            cases = cases,
            baseline = baseline,
            candidate = candidate,
            baselineStats = baselineStats,
            candidateStats = candidateStats,
            reasons = reasons,
        )

        return EvolutionEvaluationReport(
            subjectId = subject.id,
            datasetId = dataset.id,
            evaluatorId = evaluatorId,
            policyId = policy.id,
            baselineStats = baselineStats,
            candidateStats = candidateStats,
            decision = decision,
            reasons = reasons.distinct().sorted(),
            caseEvidenceIds = cases.map { it.id }.sorted(),
            observationEvidenceIds = observations.map { it.id }.sorted(),
        )
    }

    private fun decide(
        cases: List<EvolutionTestCase>,
        baseline: List<EvolutionShadowObservation>,
        candidate: List<EvolutionShadowObservation>,
        baselineStats: EvolutionEvaluationStats,
        candidateStats: EvolutionEvaluationStats,
        reasons: MutableList<String>,
    ): EvolutionEvaluationDecision {
        val candidateHardFailures = candidate.flatMap { it.hardFailures }.toSet()
        if (candidateHardFailures.isNotEmpty()) {
            candidateHardFailures.sortedBy { it.name }.forEach { reasons += "hard-failure:${it.name}" }
            return EvolutionEvaluationDecision.REJECTED
        }
        if (candidate.any { it.productiveEffectAttempted }) {
            reasons += "productive-effect-attempted"
            return EvolutionEvaluationDecision.REJECTED
        }

        if (cases.size < policy.minimumCases) {
            reasons += "insufficient-cases:${cases.size}<${policy.minimumCases}"
            return EvolutionEvaluationDecision.INSUFFICIENT_EVIDENCE
        }

        val baselineHardFailures = baseline.flatMap { it.hardFailures }.toSet()
        if (baselineHardFailures.isNotEmpty()) {
            reasons += "baseline-invalid-hard-failure"
            return EvolutionEvaluationDecision.INSUFFICIENT_EVIDENCE
        }

        if (candidateStats.successRate < policy.minimumCandidateSuccessRate) {
            reasons += "candidate-success-rate:${candidateStats.successRate}<${policy.minimumCandidateSuccessRate}"
        }
        if (candidateStats.meanQuality < policy.minimumCandidateMeanQuality) {
            reasons += "candidate-quality:${candidateStats.meanQuality}<${policy.minimumCandidateMeanQuality}"
        }
        val qualityDelta = candidateStats.meanQuality - baselineStats.meanQuality
        if (qualityDelta < policy.minimumQualityDelta) {
            reasons += "quality-delta:$qualityDelta<${policy.minimumQualityDelta}"
        }

        val latencyRatio = safeRatio(candidateStats.meanLatencyMs, baselineStats.meanLatencyMs)
        if (latencyRatio > policy.maximumLatencyRatio) {
            reasons += "latency-ratio:$latencyRatio>${policy.maximumLatencyRatio}"
        }
        val memoryRatio = safeRatio(candidateStats.meanPeakMemoryBytes, baselineStats.meanPeakMemoryBytes)
        if (memoryRatio > policy.maximumPeakMemoryRatio) {
            reasons += "memory-ratio:$memoryRatio>${policy.maximumPeakMemoryRatio}"
        }

        return if (reasons.isEmpty()) {
            reasons += "candidate-meets-independent-shadow-policy"
            EvolutionEvaluationDecision.ELIGIBLE
        } else {
            EvolutionEvaluationDecision.NOT_BETTER
        }
    }

    private fun safeRatio(candidate: Double, baseline: Double): Double = when {
        baseline == 0.0 && candidate == 0.0 -> 1.0
        baseline == 0.0 -> Double.POSITIVE_INFINITY
        else -> candidate / baseline
    }
}
