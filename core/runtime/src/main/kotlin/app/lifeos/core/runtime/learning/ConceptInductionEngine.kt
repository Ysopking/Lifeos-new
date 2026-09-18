package app.lifeos.core.runtime.learning

import app.lifeos.core.field.StableFieldIds

data class ConceptInductionPolicy(
    val minimumSupport: Int = 2,
    val minimumInformationGain: Double = 0.01,
    val counterexamplePenalty: Double = 0.25,
) {
    init {
        require(minimumSupport >= 2)
        require(minimumInformationGain.isFinite() && minimumInformationGain > 0.0)
        require(counterexamplePenalty.isFinite() && counterexamplePenalty in 0.0..1.0)
    }
}

data class ConceptInductionResult(
    val cluster: CandidateCluster,
    val supportCount: Int,
    val counterexampleCount: Int,
    val informationGain: Double,
    val confidence: Double,
    val sourceCycleIds: Set<String>,
    val inductionAlgorithmVersion: String,
) {
    init {
        require(supportCount >= 2)
        require(counterexampleCount >= 0)
        require(informationGain.isFinite() && informationGain > 0.0)
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(sourceCycleIds.size >= 2)
        require(inductionAlgorithmVersion.isNotBlank())
    }

    val fingerprint: String = StableFieldIds.fingerprint(
        "concept-induction-result/v1",
        cluster.signature.fingerprint(),
        supportCount.toString(),
        counterexampleCount.toString(),
        java.lang.Double.toHexString(informationGain),
        java.lang.Double.toHexString(confidence),
        inductionAlgorithmVersion,
        *sourceCycleIds.sorted().toTypedArray(),
    )
}

class ConceptInductionEngine(
    private val policy: ConceptInductionPolicy = ConceptInductionPolicy(),
) {
    fun induce(
        clusters: List<CandidateCluster>,
        counterexampleFingerprints: Map<PatternSignature, Set<String>> = emptyMap(),
    ): List<ConceptInductionResult> =
        clusters.mapNotNull { cluster ->
            val cycles = cluster.occurrences.mapTo(linkedSetOf()) { it.cycleId }
            if (cluster.supportCount < policy.minimumSupport || cycles.size < 2) return@mapNotNull null
            val counterexamples = counterexampleFingerprints[cluster.signature].orEmpty()
            val support = cluster.supportCount.toDouble()
            val penalty = counterexamples.size * policy.counterexamplePenalty
            val informationGain = (support / (support + 1.0) - penalty / (support + 1.0))
                .coerceAtLeast(0.0)
            if (informationGain <= policy.minimumInformationGain) return@mapNotNull null
            val confidence = (
                support / (support + counterexamples.size.coerceAtLeast(1))
            ).coerceIn(0.0, 1.0)
            ConceptInductionResult(
                cluster = cluster,
                supportCount = cluster.supportCount,
                counterexampleCount = counterexamples.size,
                informationGain = informationGain,
                confidence = confidence,
                sourceCycleIds = cycles,
                inductionAlgorithmVersion = ALGORITHM_VERSION,
            )
        }.sortedByDescending { it.informationGain }

    companion object {
        const val ALGORITHM_VERSION = "structural-pattern-lite-v1"
    }
}
