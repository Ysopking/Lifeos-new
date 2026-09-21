package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds

enum class SkillShadowSubjectKind {
    PROCEDURAL,
    GENERALIZED,
}

data class SkillShadowSubject private constructor(
    val subjectId: String,
    val kind: SkillShadowSubjectKind,
    val subjectFingerprint: String,
    val stepKeys: List<String>,
    val sourceCandidateId: String?,
    val targetDomainId: String?,
) {
    init {
        require(subjectId.isNotBlank())
        require(subjectFingerprint.isNotBlank())
        require(stepKeys.isNotEmpty())
        require(stepKeys == stepKeys.distinct().sorted())
        sourceCandidateId?.let { require(it.isNotBlank()) }
        targetDomainId?.let { require(it.isNotBlank()) }
        if (kind == SkillShadowSubjectKind.PROCEDURAL) {
            require(sourceCandidateId == null)
            require(targetDomainId == null)
        } else {
            require(!sourceCandidateId.isNullOrBlank())
            require(!targetDomainId.isNullOrBlank())
        }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "skill-shadow-subject/v1",
        subjectId,
        kind.name,
        subjectFingerprint,
        sourceCandidateId.orEmpty(),
        targetDomainId.orEmpty(),
        *stepKeys.map { "step:" + it }.toTypedArray(),
    )

    companion object {
        fun from(candidate: ProceduralSkillCandidate): SkillShadowSubject =
            SkillShadowSubject(
                subjectId = candidate.id,
                kind = SkillShadowSubjectKind.PROCEDURAL,
                subjectFingerprint = candidate.fingerprint(),
                stepKeys = candidate.steps.map { it.key }.sorted(),
                sourceCandidateId = null,
                targetDomainId = null,
            )

        fun from(candidate: GeneralizedSkillCandidate): SkillShadowSubject =
            SkillShadowSubject(
                subjectId = candidate.id,
                kind = SkillShadowSubjectKind.GENERALIZED,
                subjectFingerprint = candidate.fingerprint,
                stepKeys = candidate.steps.map { it.sourceStepKey }.sorted(),
                sourceCandidateId = candidate.sourceSkillId,
                targetDomainId = candidate.targetDomainId,
            )
    }
}

data class SkillShadowTestCase(
    val caseId: String,
    val inputFingerprint: String,
    val expectedOutputFingerprint: String?,
    val protectedCase: Boolean = false,
) {
    init {
        require(caseId.isNotBlank())
        require(inputFingerprint.isNotBlank())
        expectedOutputFingerprint?.let { require(it.isNotBlank()) }
        if (protectedCase) require(!expectedOutputFingerprint.isNullOrBlank()) {
            "Protected skill-shadow cases require an exact expected output fingerprint"
        }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "skill-shadow-test-case/v1",
        caseId,
        inputFingerprint,
        expectedOutputFingerprint.orEmpty(),
        protectedCase.toString(),
    )
}

enum class SkillShadowExecutionMode {
    SHADOW,
}

data class SkillShadowObservation(
    val subjectId: String,
    val caseId: String,
    val executionMode: SkillShadowExecutionMode,
    val outputFingerprint: String?,
    val success: Boolean,
    val quality: Double,
    val productiveEffectAttempted: Boolean,
    val safetyViolation: Boolean,
    val latencyMs: Long,
    val evidenceFingerprint: String,
) {
    init {
        require(subjectId.isNotBlank())
        require(caseId.isNotBlank())
        outputFingerprint?.let { require(it.isNotBlank()) }
        require(quality.isFinite() && quality in 0.0..1.0)
        require(latencyMs >= 0L)
        require(evidenceFingerprint.isNotBlank())
        if (success) require(!outputFingerprint.isNullOrBlank()) {
            "Successful skill-shadow observations require an output fingerprint"
        }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "skill-shadow-observation/v1",
        subjectId,
        caseId,
        executionMode.name,
        outputFingerprint.orEmpty(),
        success.toString(),
        java.lang.Double.toHexString(quality),
        productiveEffectAttempted.toString(),
        safetyViolation.toString(),
        latencyMs.toString(),
        evidenceFingerprint,
    )
}

data class SkillShadowValidationPolicy(
    val minimumCases: Int = 3,
    val minimumSuccessRate: Double = 1.0,
    val minimumMeanQuality: Double = 0.80,
    val maximumMeanLatencyMs: Double = 5_000.0,
) {
    init {
        require(minimumCases in 1..256)
        require(minimumSuccessRate.isFinite() && minimumSuccessRate in 0.0..1.0)
        require(minimumMeanQuality.isFinite() && minimumMeanQuality in 0.0..1.0)
        require(maximumMeanLatencyMs.isFinite() && maximumMeanLatencyMs >= 0.0)
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "skill-shadow-validation-policy/v1",
        minimumCases.toString(),
        java.lang.Double.toHexString(minimumSuccessRate),
        java.lang.Double.toHexString(minimumMeanQuality),
        java.lang.Double.toHexString(maximumMeanLatencyMs),
    )
}

data class SkillShadowValidationStats(
    val cases: Int,
    val successes: Int,
    val exactExpectedOutputs: Int,
    val protectedCases: Int,
    val protectedCasesPassed: Int,
    val safetyViolations: Int,
    val productiveEffectAttempts: Int,
    val meanQuality: Double,
    val meanLatencyMs: Double,
) {
    init {
        require(cases >= 0)
        require(successes in 0..cases)
        require(exactExpectedOutputs in 0..cases)
        require(protectedCases in 0..cases)
        require(protectedCasesPassed in 0..protectedCases)
        require(safetyViolations in 0..cases)
        require(productiveEffectAttempts in 0..cases)
        require(meanQuality.isFinite() && meanQuality in 0.0..1.0)
        require(meanLatencyMs.isFinite() && meanLatencyMs >= 0.0)
    }

    val successRate: Double
        get() = if (cases == 0) 0.0 else successes.toDouble() / cases
}

enum class SkillShadowValidationDecision {
    VALIDATED,
    INSUFFICIENT_EVIDENCE,
    REJECTED,
}

data class SkillShadowValidationReport(
    val subjectFingerprint: String,
    val evaluatorId: String,
    val policyFingerprint: String,
    val decision: SkillShadowValidationDecision,
    val stats: SkillShadowValidationStats,
    val reasons: List<String>,
    val caseFingerprints: List<String>,
    val observationFingerprints: List<String>,
    val fingerprint: String,
) {
    init {
        require(subjectFingerprint.isNotBlank())
        require(evaluatorId.isNotBlank())
        require(policyFingerprint.isNotBlank())
        require(reasons == reasons.distinct().sorted())
        require(caseFingerprints.isNotEmpty())
        require(caseFingerprints == caseFingerprints.distinct().sorted())
        require(observationFingerprints.isNotEmpty())
        require(observationFingerprints == observationFingerprints.distinct().sorted())
        require(
            fingerprint == reportFingerprint(
                subjectFingerprint = subjectFingerprint,
                evaluatorId = evaluatorId,
                policyFingerprint = policyFingerprint,
                decision = decision,
                stats = stats,
                reasons = reasons,
                caseFingerprints = caseFingerprints,
                observationFingerprints = observationFingerprints,
            )
        )
    }

    val shadowValidated: Boolean
        get() = decision == SkillShadowValidationDecision.VALIDATED

    val promotionAllowed: Boolean
        get() = false

    val activationAllowed: Boolean
        get() = false
}

/**
 * B378 deterministic, side-effect-free shadow validator for B376/B377 skill candidates.
 *
 * Observations must already have been produced in an isolated SHADOW execution environment.
 * This evaluator never executes a skill and never promotes/activates one. Any productive-effect
 * attempt or safety violation rejects the report immediately.
 */
class SkillShadowValidationEngine(
    private val policy: SkillShadowValidationPolicy = SkillShadowValidationPolicy(),
) {
    fun evaluate(
        subject: SkillShadowSubject,
        evaluatorId: String,
        cases: Collection<SkillShadowTestCase>,
        observations: Collection<SkillShadowObservation>,
    ): SkillShadowValidationReport {
        require(evaluatorId.isNotBlank())
        require(evaluatorId != subject.subjectId) {
            "Skill candidate cannot evaluate itself"
        }
        require(cases.isNotEmpty()) { "Skill shadow validation requires cases" }
        require(cases.map { it.caseId }.distinct().size == cases.size) {
            "Skill shadow case ids must be unique"
        }
        require(cases.map { it.inputFingerprint }.distinct().size == cases.size) {
            "Skill shadow inputs must be distinct"
        }

        val caseById = cases.associateBy { it.caseId }
        require(observations.size == cases.size) {
            "Every skill shadow case requires exactly one observation"
        }
        require(observations.map { it.caseId }.toSet() == caseById.keys) {
            "Skill shadow observations must cover the exact case set"
        }
        require(observations.map { it.caseId }.distinct().size == observations.size) {
            "Skill shadow accepts exactly one observation per case"
        }
        require(observations.all { it.subjectId == subject.subjectId }) {
            "Skill shadow observation belongs to another candidate"
        }
        require(observations.all { it.executionMode == SkillShadowExecutionMode.SHADOW }) {
            "Skill shadow observations must be produced in SHADOW mode"
        }

        val orderedCases = cases.sortedBy { it.caseId }
        val observedByCase = observations.associateBy { it.caseId }
        val orderedObservations = orderedCases.map { observedByCase.getValue(it.caseId) }
        val exactExpectedOutputs = orderedCases.count { testCase ->
            val expected = testCase.expectedOutputFingerprint ?: return@count false
            observedByCase.getValue(testCase.caseId).outputFingerprint == expected
        }
        val protectedPassed = orderedCases.count { testCase ->
            if (!testCase.protectedCase) return@count false
            observedByCase.getValue(testCase.caseId).outputFingerprint ==
                testCase.expectedOutputFingerprint
        }
        val stats = SkillShadowValidationStats(
            cases = orderedCases.size,
            successes = orderedObservations.count { it.success },
            exactExpectedOutputs = exactExpectedOutputs,
            protectedCases = orderedCases.count { it.protectedCase },
            protectedCasesPassed = protectedPassed,
            safetyViolations = orderedObservations.count { it.safetyViolation },
            productiveEffectAttempts = orderedObservations.count { it.productiveEffectAttempted },
            meanQuality = orderedObservations.map { it.quality }.average(),
            meanLatencyMs = orderedObservations.map { it.latencyMs.toDouble() }.average(),
        )

        val reasons = mutableListOf<String>()
        val decision = decide(orderedCases, orderedObservations, stats, reasons)
        val canonicalReasons = reasons.distinct().sorted()
        val caseFingerprints = orderedCases.map { it.fingerprint() }.sorted()
        val observationFingerprints = orderedObservations.map { it.fingerprint() }.sorted()
        val policyFingerprint = policy.fingerprint()
        val fingerprint = reportFingerprint(
            subjectFingerprint = subject.fingerprint(),
            evaluatorId = evaluatorId,
            policyFingerprint = policyFingerprint,
            decision = decision,
            stats = stats,
            reasons = canonicalReasons,
            caseFingerprints = caseFingerprints,
            observationFingerprints = observationFingerprints,
        )
        return SkillShadowValidationReport(
            subjectFingerprint = subject.fingerprint(),
            evaluatorId = evaluatorId,
            policyFingerprint = policyFingerprint,
            decision = decision,
            stats = stats,
            reasons = canonicalReasons,
            caseFingerprints = caseFingerprints,
            observationFingerprints = observationFingerprints,
            fingerprint = fingerprint,
        )
    }

    private fun decide(
        cases: List<SkillShadowTestCase>,
        observations: List<SkillShadowObservation>,
        stats: SkillShadowValidationStats,
        reasons: MutableList<String>,
    ): SkillShadowValidationDecision {
        if (stats.safetyViolations > 0) {
            reasons += "safety-violation"
            return SkillShadowValidationDecision.REJECTED
        }
        if (stats.productiveEffectAttempts > 0) {
            reasons += "productive-effect-attempted"
            return SkillShadowValidationDecision.REJECTED
        }

        val observedByCase = observations.associateBy { it.caseId }
        val exactMismatches = cases.mapNotNull { testCase ->
            val expected = testCase.expectedOutputFingerprint ?: return@mapNotNull null
            if (observedByCase.getValue(testCase.caseId).outputFingerprint == expected) {
                null
            } else {
                testCase.caseId
            }
        }
        if (exactMismatches.isNotEmpty()) {
            exactMismatches.sorted().forEach { reasons += "expected-output-mismatch:" + it }
            return SkillShadowValidationDecision.REJECTED
        }

        if (cases.size < policy.minimumCases) {
            reasons += "insufficient-cases:" + cases.size + "<" + policy.minimumCases
            return SkillShadowValidationDecision.INSUFFICIENT_EVIDENCE
        }
        if (stats.successRate < policy.minimumSuccessRate) {
            reasons += "success-rate:" + stats.successRate + "<" + policy.minimumSuccessRate
        }
        if (stats.meanQuality < policy.minimumMeanQuality) {
            reasons += "mean-quality:" + stats.meanQuality + "<" + policy.minimumMeanQuality
        }
        if (stats.meanLatencyMs > policy.maximumMeanLatencyMs) {
            reasons += "mean-latency:" + stats.meanLatencyMs + ">" + policy.maximumMeanLatencyMs
        }
        if (stats.protectedCasesPassed != stats.protectedCases) {
            reasons += "protected-case-regression"
        }

        return if (reasons.isEmpty()) {
            reasons += "candidate-meets-skill-shadow-policy"
            SkillShadowValidationDecision.VALIDATED
        } else {
            SkillShadowValidationDecision.REJECTED
        }
    }
}

private fun reportFingerprint(
    subjectFingerprint: String,
    evaluatorId: String,
    policyFingerprint: String,
    decision: SkillShadowValidationDecision,
    stats: SkillShadowValidationStats,
    reasons: List<String>,
    caseFingerprints: List<String>,
    observationFingerprints: List<String>,
): String = StableFieldIds.fingerprint(
    "skill-shadow-validation-report/v1",
    subjectFingerprint,
    evaluatorId,
    policyFingerprint,
    decision.name,
    stats.cases.toString(),
    stats.successes.toString(),
    stats.exactExpectedOutputs.toString(),
    stats.protectedCases.toString(),
    stats.protectedCasesPassed.toString(),
    stats.safetyViolations.toString(),
    stats.productiveEffectAttempts.toString(),
    java.lang.Double.toHexString(stats.meanQuality),
    java.lang.Double.toHexString(stats.meanLatencyMs),
    *reasons.sorted().map { "reason:" + it }.toTypedArray(),
    *caseFingerprints.sorted().map { "case:" + it }.toTypedArray(),
    *observationFingerprints.sorted().map { "observation:" + it }.toTypedArray(),
)
