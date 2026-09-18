package app.lifeos.core.runtime.level7

import app.lifeos.core.field.StableFieldIds

data class StrategyGeneralizationResult(
    val strategy: StrategyLearningCandidate,
    val sharedActionFingerprint: String,
    val sourceTransitionCount: Int,
) {
    init {
        require(sourceTransitionCount >= 2)
        require(sharedActionFingerprint.isNotBlank())
    }
}

class StrategyGeneralizer {
    fun generalize(
        strategyId: String,
        transitions: Collection<VerifiedWorldTransition>,
    ): StrategyGeneralizationResult {
        val verified = transitions.filter { it.independentVerification }
        require(verified.size >= 2) {
            "Strategy induction requires at least two independently verified transitions"
        }
        val actions = verified.map { it.actionFingerprint }.distinct()
        require(actions.size == 1) {
            "Strategy generalization requires a common action/plan structure"
        }
        val strategyFingerprint = StableFieldIds.fingerprint(
            "strategy-generalizer/v1",
            strategyId,
            actions.single(),
            *verified.sortedBy { it.beforeSnapshotId }.flatMap {
                listOf(
                    it.beforeSnapshotId,
                    it.afterSnapshotId,
                    it.outcomeEvidenceFingerprint,
                )
            }.toTypedArray(),
        )
        return StrategyGeneralizationResult(
            strategy = StrategyLearningCandidate.create(
                strategyId = strategyId,
                strategyFingerprint = strategyFingerprint,
                transitions = verified,
            ),
            sharedActionFingerprint = actions.single(),
            sourceTransitionCount = verified.size,
        )
    }
}

data class PredictionOutcomePair(
    val rawConfidence: RawConfidence,
    val succeeded: Boolean,
    val evidenceFingerprint: String,
) {
    init { require(evidenceFingerprint.isNotBlank()) }
}

data class EmpiricalCalibrationModel(
    val fingerprint: String,
    val bins: List<Pair<Double, Double>>,
    val sampleCount: Int,
) {
    init {
        require(fingerprint.isNotBlank())
        require(sampleCount > 0)
        require(bins.isNotEmpty())
    }

    fun calibrate(raw: RawConfidence): CalibratedConfidence {
        val nearest = bins.minBy { kotlin.math.abs(it.first - raw.value) }
        return CalibratedConfidence(nearest.second)
    }
}

class EmpiricalConfidenceCalibrator(
    private val minimumSamples: Int = 8,
    private val binCount: Int = 10,
) {
    init {
        require(minimumSamples >= 2)
        require(binCount in 2..100)
    }

    fun fit(pairs: List<PredictionOutcomePair>): EmpiricalCalibrationModel {
        require(pairs.size >= minimumSamples)
        val grouped = pairs.groupBy { pair ->
            minOf(binCount - 1, (pair.rawConfidence.value * binCount).toInt())
        }
        val bins = grouped.entries.sortedBy { it.key }.map { (index, items) ->
            val center = (index + 0.5) / binCount
            val empirical = items.count { it.succeeded }.toDouble() / items.size
            center to empirical
        }
        val fingerprint = StableFieldIds.fingerprint(
            "empirical-calibration-model/v1",
            pairs.size.toString(),
            binCount.toString(),
            *bins.flatMap {
                listOf(
                    java.lang.Double.toHexString(it.first),
                    java.lang.Double.toHexString(it.second),
                )
            }.toTypedArray(),
        )
        return EmpiricalCalibrationModel(fingerprint, bins, pairs.size)
    }
}

data class MetaAdaptationEvaluation(
    val candidate: MetaAdaptationCandidate,
    val holdoutImprovement: Double,
    val regressionCount: Int,
) {
    init {
        require(holdoutImprovement.isFinite())
        require(regressionCount >= 0)
    }

    val promotableCandidate: Boolean
        get() = holdoutImprovement > 0.0 && regressionCount == 0
}

class MetaAdaptationEvaluator {
    fun evaluate(
        candidate: MetaAdaptationCandidate,
        baselineScore: Double,
        candidateScore: Double,
        regressionCount: Int,
    ): MetaAdaptationEvaluation {
        require(baselineScore.isFinite() && candidateScore.isFinite())
        return MetaAdaptationEvaluation(
            candidate = candidate,
            holdoutImprovement = candidateScore - baselineScore,
            regressionCount = regressionCount,
        )
    }
}

class StructuralSimilarityEngine {
    fun similarity(
        source: StructuralSignature,
        target: StructuralSignature,
    ): Double {
        val components = listOf(
            source.topologyFingerprint == target.topologyFingerprint,
            source.relationFingerprint == target.relationFingerprint,
            source.dimensionFingerprint == target.dimensionFingerprint,
        )
        return components.count { it }.toDouble() / components.size
    }

    fun candidate(
        source: StructuralSignature,
        target: StructuralSignature,
        validationFingerprint: String,
    ): StructuralTransferCandidate =
        StructuralTransferCandidate.create(
            source = source,
            target = target,
            structuralSimilarity = similarity(source, target),
            validationFingerprint = validationFingerprint,
        )
}

class FailureToCurriculumProjector {
    fun project(
        generatorId: String,
        evaluatorId: String,
        failures: Collection<PredictionFailure>,
    ): CurriculumCandidate {
        require(failures.isNotEmpty())
        val curriculumFingerprint = StableFieldIds.fingerprint(
            "failure-to-curriculum/v1",
            *failures.sortedBy { it.predictionId }.flatMap {
                listOf(
                    it.predictionId,
                    it.failureClass,
                    it.evidenceFingerprint,
                )
            }.toTypedArray(),
        )
        return CurriculumCandidate.create(
            generatorId = generatorId,
            evaluatorId = evaluatorId,
            failures = failures,
            curriculumFingerprint = curriculumFingerprint,
        )
    }
}

data class EvidenceOpportunity(
    val kind: EvidenceActionKind,
    val uncertaintyReduction: Double,
    val goalRelevance: Double,
    val sourceReliability: Double,
    val resourceCost: Double,
    val rationale: String,
) {
    init {
        require(uncertaintyReduction.isFinite() && uncertaintyReduction in 0.0..1.0)
        require(goalRelevance.isFinite() && goalRelevance in 0.0..1.0)
        require(sourceReliability.isFinite() && sourceReliability in 0.0..1.0)
        require(resourceCost.isFinite() && resourceCost > 0.0)
        require(rationale.isNotBlank())
    }
}

object InformationGainEstimator {
    fun score(opportunity: EvidenceOpportunity): Double =
        opportunity.uncertaintyReduction *
            opportunity.goalRelevance *
            opportunity.sourceReliability /
            opportunity.resourceCost
}

class ActiveEvidencePlanner {
    fun select(
        sourceCycleId: String,
        gapFingerprint: String,
        budgetFingerprint: String,
        opportunities: List<EvidenceOpportunity>,
        limit: Int = 4,
    ): List<EvidenceActionRequest> {
        require(limit in 1..16)
        return opportunities
            .sortedWith(
                compareByDescending<EvidenceOpportunity> { InformationGainEstimator.score(it) }
                    .thenBy { it.kind.name }
            )
            .take(limit)
            .map {
                EvidenceActionRequest.create(
                    sourceCycleId = sourceCycleId,
                    gapFingerprint = gapFingerprint,
                    kind = it.kind,
                    rationale = it.rationale,
                    budgetFingerprint = budgetFingerprint,
                )
            }
    }
}
