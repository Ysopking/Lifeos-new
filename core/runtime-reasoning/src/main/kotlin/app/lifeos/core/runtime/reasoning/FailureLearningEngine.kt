package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.level7.CurriculumCandidate
import app.lifeos.core.runtime.level7.FailureToCurriculumProjector
import app.lifeos.core.runtime.level7.PredictionFailure

enum class FailureLearningSourceKind {
    PREDICTION_ERROR,
    SKILL_SHADOW,
}

data class FailureLearningResult(
    val generatorId: String,
    val evaluatorId: String,
    val predictionErrorReportFingerprints: List<String>,
    val skillShadowReportFingerprints: List<String>,
    val failures: List<PredictionFailure>,
    val curriculumCandidate: CurriculumCandidate?,
    val fingerprint: String,
) {
    init {
        require(generatorId.isNotBlank())
        require(evaluatorId.isNotBlank())
        require(generatorId != evaluatorId)
        require(
            predictionErrorReportFingerprints ==
                predictionErrorReportFingerprints.distinct().sorted()
        )
        require(
            skillShadowReportFingerprints ==
                skillShadowReportFingerprints.distinct().sorted()
        )
        require(failures == failures.distinctBy(::predictionFailureFingerprint)
            .sortedBy(::predictionFailureFingerprint))
        if (failures.isEmpty()) {
            require(curriculumCandidate == null)
        } else {
            require(curriculumCandidate != null)
            require(!curriculumCandidate.strategyPromotionAllowed)
        }
        require(
            fingerprint == failureLearningResultFingerprint(
                generatorId = generatorId,
                evaluatorId = evaluatorId,
                predictionErrorReportFingerprints = predictionErrorReportFingerprints,
                skillShadowReportFingerprints = skillShadowReportFingerprints,
                failures = failures,
                curriculumCandidate = curriculumCandidate,
            )
        )
    }

    val hasFailures: Boolean
        get() = failures.isNotEmpty()

    val learningAuthority: Boolean
        get() = false

    val strategyPromotionAllowed: Boolean
        get() = false

    val correctiveExecutionAllowed: Boolean
        get() = false
}

/**
 * B379 normalizes verified B372 prediction misses and rejected B378 shadow evaluations into the
 * existing level7 PredictionFailure/CurriculumCandidate path.
 *
 * Missing, incomplete, unverified, within-band, and insufficient-evidence observations are not
 * failures. This engine records reviewable failure evidence only; it never promotes a strategy,
 * executes a correction, or mutates productive state.
 */
class FailureLearningEngine(
    private val curriculumProjector: FailureToCurriculumProjector =
        FailureToCurriculumProjector(),
) {
    fun learn(
        generatorId: String,
        evaluatorId: String,
        predictionReports: Collection<PredictionErrorReport> = emptyList(),
        skillShadowReports: Collection<SkillShadowValidationReport> = emptyList(),
    ): FailureLearningResult {
        require(generatorId.isNotBlank())
        require(evaluatorId.isNotBlank())
        require(generatorId != evaluatorId) {
            "Failure-learning generator and evaluator must be separated"
        }

        val canonicalPredictionReports = predictionReports
            .distinctBy { it.fingerprint }
            .sortedBy { it.fingerprint }
        require(canonicalPredictionReports.size == predictionReports.size) {
            "Duplicate prediction-error reports are not allowed"
        }
        val canonicalShadowReports = skillShadowReports
            .distinctBy { it.fingerprint }
            .sortedBy { it.fingerprint }
        require(canonicalShadowReports.size == skillShadowReports.size) {
            "Duplicate skill-shadow reports are not allowed"
        }

        val failures = buildList {
            canonicalPredictionReports.forEach { report ->
                report.entries
                    .filter {
                        it.state == PredictionErrorState.OUTSIDE_EXPECTED_BAND &&
                            it.learningEligible
                    }
                    .forEach { entry ->
                        val observed = requireNotNull(entry.observationInputFingerprint) {
                            "Verified prediction error must retain exact observed input fingerprint"
                        }
                        add(
                            PredictionFailure(
                                predictionId = entry.evidenceActionId,
                                expectedFingerprint = entry.expectationFingerprint,
                                observedFingerprint = observed,
                                failureClass = "prediction-outside-expected-band",
                                evidenceFingerprint = entry.fingerprint,
                            )
                        )
                    }
            }
            canonicalShadowReports
                .filter { it.decision == SkillShadowValidationDecision.REJECTED }
                .forEach { report ->
                    report.reasons.forEach { reason ->
                        add(
                            PredictionFailure(
                                predictionId = "skill-shadow:" + report.subjectFingerprint,
                                expectedFingerprint = StableFieldIds.fingerprint(
                                    "skill-shadow-expected-pass/v1",
                                    report.subjectFingerprint,
                                    report.policyFingerprint,
                                    reason,
                                ),
                                observedFingerprint = report.fingerprint,
                                failureClass = "skill-shadow:" + reason,
                                evidenceFingerprint = report.fingerprint,
                            )
                        )
                    }
                }
        }
            .distinctBy(::predictionFailureFingerprint)
            .sortedBy(::predictionFailureFingerprint)

        val curriculum = if (failures.isEmpty()) {
            null
        } else {
            curriculumProjector.project(
                generatorId = generatorId,
                evaluatorId = evaluatorId,
                failures = failures,
            )
        }
        val predictionFingerprints = canonicalPredictionReports.map { it.fingerprint }
        val shadowFingerprints = canonicalShadowReports.map { it.fingerprint }
        val fingerprint = failureLearningResultFingerprint(
            generatorId = generatorId,
            evaluatorId = evaluatorId,
            predictionErrorReportFingerprints = predictionFingerprints,
            skillShadowReportFingerprints = shadowFingerprints,
            failures = failures,
            curriculumCandidate = curriculum,
        )
        return FailureLearningResult(
            generatorId = generatorId,
            evaluatorId = evaluatorId,
            predictionErrorReportFingerprints = predictionFingerprints,
            skillShadowReportFingerprints = shadowFingerprints,
            failures = failures,
            curriculumCandidate = curriculum,
            fingerprint = fingerprint,
        )
    }
}

private fun predictionFailureFingerprint(
    failure: PredictionFailure,
): String = StableFieldIds.fingerprint(
    "level7-prediction-failure/v1",
    failure.predictionId,
    failure.expectedFingerprint,
    failure.observedFingerprint,
    failure.failureClass,
    failure.evidenceFingerprint,
)

private fun failureLearningResultFingerprint(
    generatorId: String,
    evaluatorId: String,
    predictionErrorReportFingerprints: List<String>,
    skillShadowReportFingerprints: List<String>,
    failures: List<PredictionFailure>,
    curriculumCandidate: CurriculumCandidate?,
): String = StableFieldIds.fingerprint(
    "failure-learning-result/v1",
    generatorId,
    evaluatorId,
    curriculumCandidate?.fingerprint().orEmpty(),
    *predictionErrorReportFingerprints.sorted()
        .map { "prediction-report:" + it }
        .toTypedArray(),
    *skillShadowReportFingerprints.sorted()
        .map { "shadow-report:" + it }
        .toTypedArray(),
    *failures.map(::predictionFailureFingerprint)
        .sorted()
        .map { "failure:" + it }
        .toTypedArray(),
)
