package app.lifeos.core.runtime.learning

import app.lifeos.core.field.StableFieldIds

enum class OutcomeScoreState {
    VERIFIED,
    INSUFFICIENT_EVIDENCE,
    CONFLICTED,
}

data class OutcomeScoringPolicy(
    val minimumIndependentEvidence: Int = 1,
    val minimumEvidenceConfidence: Double = 0.55,
    val maximumIndependentDisagreement: Double = 0.45,
    val completionWeight: Double = 0.20,
    val correctnessWeight: Double = 0.35,
    val usefulnessWeight: Double = 0.20,
    val policyComplianceWeight: Double = 0.25,
) {
    init {
        require(minimumIndependentEvidence in 1..64)
        require(minimumEvidenceConfidence.isFinite() && minimumEvidenceConfidence in 0.0..1.0)
        require(maximumIndependentDisagreement.isFinite() && maximumIndependentDisagreement in 0.0..1.0)
        val weights = listOf(completionWeight, correctnessWeight, usefulnessWeight, policyComplianceWeight)
        require(weights.all { it.isFinite() && it >= 0.0 })
        require(weights.sum() > 0.0) { "Outcome scoring requires positive component weight" }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "outcome-scoring-policy/v1",
        minimumIndependentEvidence.toString(),
        java.lang.Double.toHexString(minimumEvidenceConfidence),
        java.lang.Double.toHexString(maximumIndependentDisagreement),
        java.lang.Double.toHexString(completionWeight),
        java.lang.Double.toHexString(correctnessWeight),
        java.lang.Double.toHexString(usefulnessWeight),
        java.lang.Double.toHexString(policyComplianceWeight),
    )
}

@JvmInline
value class OutcomeScoreId(val value: String) {
    init {
        require(value.startsWith(PREFIX)) { "Invalid outcome score id" }
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}"))) {
            "Invalid outcome score digest"
        }
    }

    override fun toString(): String = value

    companion object {
        const val PREFIX = "outcome-score:"
    }
}

data class OutcomeComponentScores(
    val completion: Double?,
    val correctness: Double?,
    val usefulness: Double?,
    val policyCompliance: Double?,
) {
    init {
        require(listOfNotNull(completion, correctness, usefulness, policyCompliance).all {
            it.isFinite() && it in 0.0..1.0
        })
    }
}

data class OutcomeScore(
    val id: OutcomeScoreId,
    val predictionId: OutcomePredictionId,
    val state: OutcomeScoreState,
    val quality: Double,
    val signedScore: Double,
    val components: OutcomeComponentScores,
    val allEvidenceIds: List<OutcomeEvidenceId>,
    val independentEvidenceIds: List<OutcomeEvidenceId>,
    val policyFingerprint: String,
    val reasons: List<String>,
) {
    init {
        require(quality.isFinite() && quality in 0.0..1.0)
        require(signedScore.isFinite() && signedScore in -1.0..1.0)
        require(allEvidenceIds == allEvidenceIds.distinct().sortedBy { it.value })
        require(independentEvidenceIds == independentEvidenceIds.distinct().sortedBy { it.value })
        require(independentEvidenceIds.all(allEvidenceIds::contains))
        require(policyFingerprint.isNotBlank())
        require(reasons.isNotEmpty() && reasons.none { it.isBlank() })
        if (state != OutcomeScoreState.VERIFIED) {
            require(signedScore == 0.0) { "Unresolved outcome score cannot drive adaptation" }
        }
        require(id == expectedId()) { "Outcome score id/content mismatch" }
    }

    val adaptationAllowed: Boolean
        get() = state == OutcomeScoreState.VERIFIED && independentEvidenceIds.isNotEmpty()

    fun contentFingerprint(): String = fingerprint(
        predictionId = predictionId,
        state = state,
        quality = quality,
        signedScore = signedScore,
        components = components,
        allEvidenceIds = allEvidenceIds,
        independentEvidenceIds = independentEvidenceIds,
        policyFingerprint = policyFingerprint,
        reasons = reasons,
    )

    private fun expectedId(): OutcomeScoreId =
        OutcomeScoreId("${OutcomeScoreId.PREFIX}${contentFingerprint()}")

    companion object {
        fun create(
            predictionId: OutcomePredictionId,
            state: OutcomeScoreState,
            quality: Double,
            signedScore: Double,
            components: OutcomeComponentScores,
            allEvidenceIds: List<OutcomeEvidenceId>,
            independentEvidenceIds: List<OutcomeEvidenceId>,
            policyFingerprint: String,
            reasons: List<String>,
        ): OutcomeScore {
            val all = allEvidenceIds.distinct().sortedBy { it.value }
            val independent = independentEvidenceIds.distinct().sortedBy { it.value }
            val canonicalReasons = reasons.distinct().sorted()
            val fingerprint = fingerprint(
                predictionId = predictionId,
                state = state,
                quality = quality,
                signedScore = signedScore,
                components = components,
                allEvidenceIds = all,
                independentEvidenceIds = independent,
                policyFingerprint = policyFingerprint,
                reasons = canonicalReasons,
            )
            return OutcomeScore(
                id = OutcomeScoreId("${OutcomeScoreId.PREFIX}$fingerprint"),
                predictionId = predictionId,
                state = state,
                quality = quality,
                signedScore = signedScore,
                components = components,
                allEvidenceIds = all,
                independentEvidenceIds = independent,
                policyFingerprint = policyFingerprint,
                reasons = canonicalReasons,
            )
        }

        private fun fingerprint(
            predictionId: OutcomePredictionId,
            state: OutcomeScoreState,
            quality: Double,
            signedScore: Double,
            components: OutcomeComponentScores,
            allEvidenceIds: List<OutcomeEvidenceId>,
            independentEvidenceIds: List<OutcomeEvidenceId>,
            policyFingerprint: String,
            reasons: List<String>,
        ): String = StableFieldIds.fingerprint(
            "outcome-score/v1",
            predictionId.value,
            state.name,
            java.lang.Double.toHexString(quality),
            java.lang.Double.toHexString(signedScore),
            componentPart("completion", components.completion),
            componentPart("correctness", components.correctness),
            componentPart("usefulness", components.usefulness),
            componentPart("policy", components.policyCompliance),
            policyFingerprint,
            *allEvidenceIds.map { "evidence:${it.value}" }.sorted().toTypedArray(),
            *independentEvidenceIds.map { "independent:${it.value}" }.sorted().toTypedArray(),
            *reasons.sorted().map { "reason:$it" }.toTypedArray(),
        )

        private fun componentPart(name: String, value: Double?): String =
            "$name:${value?.let(java.lang.Double::toHexString) ?: "-"}"
    }
}

class OutcomeScorer(
    private val policy: OutcomeScoringPolicy = OutcomeScoringPolicy(),
) {
    fun score(
        prediction: OutcomePrediction,
        evidence: List<OutcomeEvidence>,
    ): OutcomeScore {
        require(evidence.map { it.id }.distinct().size == evidence.size) {
            "Outcome evidence ids must be unique"
        }
        require(evidence.all { it.predictionId == prediction.id }) {
            "Outcome evidence must reference the prediction being scored"
        }
        require(evidence.all { it.observedAt >= prediction.createdAt }) {
            "Outcome evidence cannot predate its prediction"
        }

        val allEvidence = evidence.sortedBy { it.id.value }
        val independent = allEvidence.filter { item ->
            item.confidence >= policy.minimumEvidenceConfidence && item.isIndependentFor(prediction)
        }
        val policyFingerprint = policy.fingerprint()

        if (independent.size < policy.minimumIndependentEvidence) {
            return OutcomeScore.create(
                predictionId = prediction.id,
                state = OutcomeScoreState.INSUFFICIENT_EVIDENCE,
                quality = 0.5,
                signedScore = 0.0,
                components = OutcomeComponentScores(null, null, null, null),
                allEvidenceIds = allEvidence.map { it.id },
                independentEvidenceIds = independent.map { it.id },
                policyFingerprint = policyFingerprint,
                reasons = listOf(
                    "independent-evidence:${independent.size}:required:${policy.minimumIndependentEvidence}"
                ),
            )
        }

        val independentComposites = independent.map { evidenceComposite(it) }
        val disagreement = (independentComposites.maxOrNull() ?: 0.0) -
            (independentComposites.minOrNull() ?: 0.0)
        if (disagreement > policy.maximumIndependentDisagreement) {
            return OutcomeScore.create(
                predictionId = prediction.id,
                state = OutcomeScoreState.CONFLICTED,
                quality = weightedAverage(independentComposites, independent.map(::evidenceWeight)),
                signedScore = 0.0,
                components = aggregateComponents(independent),
                allEvidenceIds = allEvidence.map { it.id },
                independentEvidenceIds = independent.map { it.id },
                policyFingerprint = policyFingerprint,
                reasons = listOf(
                    "independent-evidence-conflict:${java.lang.Double.toHexString(disagreement)}"
                ),
            )
        }

        val components = aggregateComponents(independent)
        val quality = componentComposite(components)
        return OutcomeScore.create(
            predictionId = prediction.id,
            state = OutcomeScoreState.VERIFIED,
            quality = quality,
            signedScore = (quality * 2.0 - 1.0).coerceIn(-1.0, 1.0),
            components = components,
            allEvidenceIds = allEvidence.map { it.id },
            independentEvidenceIds = independent.map { it.id },
            policyFingerprint = policyFingerprint,
            reasons = listOf("verified-independent-outcome-evidence"),
        )
    }

    private fun aggregateComponents(evidence: List<OutcomeEvidence>): OutcomeComponentScores =
        OutcomeComponentScores(
            completion = aggregate(evidence) { it.signal.completion },
            correctness = aggregate(evidence) { it.signal.correctness },
            usefulness = aggregate(evidence) { it.signal.usefulness },
            policyCompliance = aggregate(evidence) { it.signal.policyCompliance },
        )

    private fun aggregate(
        evidence: List<OutcomeEvidence>,
        selector: (OutcomeEvidence) -> Double?,
    ): Double? {
        val values = evidence.mapNotNull { item ->
            selector(item)?.let { value -> value to evidenceWeight(item) }
        }
        if (values.isEmpty()) return null
        return weightedAverage(values.map { it.first }, values.map { it.second })
    }

    private fun evidenceComposite(evidence: OutcomeEvidence): Double = componentComposite(
        OutcomeComponentScores(
            completion = evidence.signal.completion,
            correctness = evidence.signal.correctness,
            usefulness = evidence.signal.usefulness,
            policyCompliance = evidence.signal.policyCompliance,
        )
    )

    private fun componentComposite(components: OutcomeComponentScores): Double {
        val weighted = buildList {
            components.completion?.let { add(it to policy.completionWeight) }
            components.correctness?.let { add(it to policy.correctnessWeight) }
            components.usefulness?.let { add(it to policy.usefulnessWeight) }
            components.policyCompliance?.let { add(it to policy.policyComplianceWeight) }
        }
        if (weighted.isEmpty()) return 0.5
        val denominator = weighted.sumOf { it.second }
        return if (denominator == 0.0) 0.5 else
            (weighted.sumOf { it.first * it.second } / denominator).coerceIn(0.0, 1.0)
    }

    private fun evidenceWeight(evidence: OutcomeEvidence): Double =
        evidence.confidence * when (evidence.sourceClass) {
            OutcomeEvidenceSourceClass.ACTION_SELF_REPORT -> 0.0
            OutcomeEvidenceSourceClass.SYSTEM_OBSERVATION -> 0.85
            OutcomeEvidenceSourceClass.USER_CORRECTION -> 1.0
            OutcomeEvidenceSourceClass.EXTERNAL_VERIFICATION -> 1.0
        }

    private fun weightedAverage(values: List<Double>, weights: List<Double>): Double {
        require(values.size == weights.size && values.isNotEmpty())
        val denominator = weights.sum()
        return if (denominator == 0.0) 0.5 else
            (values.indices.sumOf { index -> values[index] * weights[index] } / denominator).coerceIn(0.0, 1.0)
    }
}
