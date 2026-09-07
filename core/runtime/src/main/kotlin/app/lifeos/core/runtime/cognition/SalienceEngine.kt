package app.lifeos.core.runtime.cognition

data class SalienceVector(
    val novelty: Double = 0.0,
    val relevance: Double = 0.0,
    val urgency: Double = 0.0,
    val semanticMass: Double = 0.0,
    val confidenceImpact: Double = 0.0,
    val goalAffinity: Double = 0.0,
    val healthImpact: Double = 0.0,
) {
    init {
        listOf(
            novelty,
            relevance,
            urgency,
            semanticMass,
            confidenceImpact,
            goalAffinity,
            healthImpact,
        ).forEach { value ->
            require(value.isFinite() && value >= 0.0) {
                "Salience vector values must be finite and non-negative"
            }
        }
    }
}

fun interface SaliencePolicy {
    fun score(vector: SalienceVector): Double
}

class WeightedSaliencePolicy(
    private val noveltyWeight: Double = 1.0,
    private val relevanceWeight: Double = 2.0,
    private val urgencyWeight: Double = 2.0,
    private val semanticMassWeight: Double = 1.0,
    private val confidenceImpactWeight: Double = 1.0,
    private val goalAffinityWeight: Double = 2.0,
    private val healthImpactWeight: Double = 2.0,
) : SaliencePolicy {
    private val totalWeight = listOf(
        noveltyWeight,
        relevanceWeight,
        urgencyWeight,
        semanticMassWeight,
        confidenceImpactWeight,
        goalAffinityWeight,
        healthImpactWeight,
    ).also { weights ->
        require(weights.all { it.isFinite() && it >= 0.0 }) {
            "Salience weights must be finite and non-negative"
        }
        require(weights.any { it > 0.0 }) { "At least one salience weight must be positive" }
    }.sum()

    override fun score(vector: SalienceVector): Double = (
        vector.novelty * noveltyWeight +
            vector.relevance * relevanceWeight +
            vector.urgency * urgencyWeight +
            vector.semanticMass * semanticMassWeight +
            vector.confidenceImpact * confidenceImpactWeight +
            vector.goalAffinity * goalAffinityWeight +
            vector.healthImpact * healthImpactWeight
        ) / totalWeight
}

class SalienceEngine(
    private val policy: SaliencePolicy = WeightedSaliencePolicy(),
) {
    fun score(vector: SalienceVector): Double {
        val score = policy.score(vector)
        require(score.isFinite() && score >= 0.0) {
            "Salience policy returned invalid score"
        }
        return score
    }
}
