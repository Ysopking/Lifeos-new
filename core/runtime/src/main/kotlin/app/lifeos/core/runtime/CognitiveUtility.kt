package app.lifeos.core.runtime

/**
 * Shared fixed-point scheduling utility.
 *
 * IMPORTANT: utility is never authorization or truth. The result may only rank/allocate compute.
 * OwnerPolicy/Delegation and evidence validation remain independent authorities.
 */
data class CognitiveUtilityInput(
    val relevanceMicros: Long = 0L,
    val urgencyMicros: Long = 0L,
    val informationGainMicros: Long = 0L,
    val matterAffinityMicros: Long = 0L,
    val goalAffinityMicros: Long = 0L,
    val confidenceMicros: Long = MICROS,
    val authorityMicros: Long = 0L,
    val noveltyMicros: Long = 0L,
    val hardwareBudgetMicros: Long = MICROS,
    val costMicros: Long = 0L,
    val riskMicros: Long = 0L,
) {
    init {
        listOf(
            relevanceMicros,
            urgencyMicros,
            informationGainMicros,
            matterAffinityMicros,
            goalAffinityMicros,
            confidenceMicros,
            authorityMicros,
            noveltyMicros,
            hardwareBudgetMicros,
            costMicros,
            riskMicros,
        ).forEach { require(it in 0L..MICROS) }
    }
}

data class CognitiveUtilityWeights(
    val relevance: Long = 180_000L,
    val urgency: Long = 160_000L,
    val informationGain: Long = 140_000L,
    val matterAffinity: Long = 120_000L,
    val goalAffinity: Long = 100_000L,
    val confidence: Long = 100_000L,
    val authority: Long = 80_000L,
    val novelty: Long = 60_000L,
    val hardwareBudget: Long = 60_000L,
    val costPenalty: Long = 100_000L,
    val riskPenalty: Long = 120_000L,
) {
    init {
        listOf(
            relevance, urgency, informationGain, matterAffinity, goalAffinity,
            confidence, authority, novelty, hardwareBudget, costPenalty, riskPenalty,
        ).forEach { require(it in 0L..MICROS) }
    }
}

class CognitiveUtilityFunction(
    private val weights: CognitiveUtilityWeights = CognitiveUtilityWeights(),
) {
    fun score(input: CognitiveUtilityInput): Long {
        val positive = listOf(
            weighted(input.relevanceMicros, weights.relevance),
            weighted(input.urgencyMicros, weights.urgency),
            weighted(input.informationGainMicros, weights.informationGain),
            weighted(input.matterAffinityMicros, weights.matterAffinity),
            weighted(input.goalAffinityMicros, weights.goalAffinity),
            weighted(input.confidenceMicros, weights.confidence),
            weighted(input.authorityMicros, weights.authority),
            weighted(input.noveltyMicros, weights.novelty),
            weighted(input.hardwareBudgetMicros, weights.hardwareBudget),
        ).fold(0L, ::saturatingAdd)

        val penalty = saturatingAdd(
            weighted(input.costMicros, weights.costPenalty),
            weighted(input.riskMicros, weights.riskPenalty),
        )
        return (positive - penalty).coerceIn(0L, MICROS)
    }

    private fun weighted(value: Long, weight: Long): Long =
        (value * weight) / MICROS

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}

const val MICROS: Long = 1_000_000L
