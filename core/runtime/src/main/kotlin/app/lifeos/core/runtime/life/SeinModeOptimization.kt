package app.lifeos.core.runtime.life

import app.lifeos.core.model.StableCognitiveIds
import kotlin.math.abs

enum class SeinDimension { SELF_ALIGNMENT, PRESENT_COHERENCE, AGENCY, CONTINUITY, FRICTION }

data class SeinTarget(val desired: Double, val weight: Double) {
    init {
        require(desired.isFinite() && desired in 0.0..1.0)
        require(weight.isFinite() && weight > 0.0)
    }
}

data class SeinModeDefinition(
    val version: String,
    val targets: Map<SeinDimension, SeinTarget>,
) {
    init {
        require(version.isNotBlank())
        require(targets.keys.containsAll(SeinDimension.entries))
    }

    val fingerprint: String = StableCognitiveIds.fingerprint(
        "sein-mode-definition/v1",
        version,
        *targets.entries.sortedBy { it.key.name }.flatMap { (dimension, target) ->
            listOf(dimension.name, java.lang.Double.toHexString(target.desired), java.lang.Double.toHexString(target.weight))
        }.toTypedArray(),
    )

    companion object {
        fun stableV1() = SeinModeDefinition(
            version = "sein-mode-v1",
            targets = mapOf(
                SeinDimension.SELF_ALIGNMENT to SeinTarget(1.0, 1.0),
                SeinDimension.PRESENT_COHERENCE to SeinTarget(1.0, 1.0),
                SeinDimension.AGENCY to SeinTarget(1.0, 0.9),
                SeinDimension.CONTINUITY to SeinTarget(1.0, 0.8),
                SeinDimension.FRICTION to SeinTarget(0.0, 1.0),
            ),
        )
    }
}

data class LifeStateVector(val values: Map<SeinDimension, Double>) {
    init {
        require(values.keys.containsAll(SeinDimension.entries))
        require(values.values.all { it.isFinite() && it in 0.0..1.0 })
    }

    fun shifted(delta: Map<SeinDimension, Double>) = LifeStateVector(
        values.mapValues { (dimension, current) -> (current + (delta[dimension] ?: 0.0)).coerceIn(0.0, 1.0) },
    )

    val fingerprint: String = StableCognitiveIds.fingerprint(
        "life-state-vector/v1",
        *values.entries.sortedBy { it.key.name }.flatMap { (dimension, value) ->
            listOf(dimension.name, java.lang.Double.toHexString(value))
        }.toTypedArray(),
    )

    companion object {
        fun neutral() = LifeStateVector(SeinDimension.entries.associateWith { 0.5 })
    }
}

data class SeinEvaluation(
    val definitionFingerprint: String,
    val stateFingerprint: String,
    val score: Double,
    val deltaByDimension: Map<SeinDimension, Double>,
)

class SeinModeEvaluator(private val definition: SeinModeDefinition = SeinModeDefinition.stableV1()) {
    val definitionVersion: String get() = definition.version
    val definitionFingerprint: String get() = definition.fingerprint

    fun evaluate(state: LifeStateVector): SeinEvaluation {
        val deltas = SeinDimension.entries.associateWith { dimension ->
            definition.targets.getValue(dimension).desired - state.values.getValue(dimension)
        }
        val weightedDistance = deltas.entries.sumOf { (dimension, delta) ->
            abs(delta) * definition.targets.getValue(dimension).weight
        }
        val maxDistance = definition.targets.values.sumOf { it.weight }
        return SeinEvaluation(
            definition.fingerprint,
            state.fingerprint,
            (1.0 - weightedDistance / maxDistance).coerceIn(0.0, 1.0),
            deltas,
        )
    }
}

data class FutureDeltaCandidate(
    val id: String,
    val stateDelta: Map<SeinDimension, Double>,
    val resourceCost: Double,
    val allowed: Boolean,
    val explanation: String,
) {
    init {
        require(id.isNotBlank())
        require(stateDelta.values.all { it.isFinite() && it in -1.0..1.0 })
        require(resourceCost.isFinite() && resourceCost in 0.0..1.0)
        require(explanation.isNotBlank())
    }
}

data class RankedFutureDelta(
    val candidate: FutureDeltaCandidate,
    val beforeScore: Double,
    val afterScore: Double,
    val expectedImprovement: Double,
)

class LifePlanner(private val evaluator: SeinModeEvaluator = SeinModeEvaluator()) {
    fun rank(current: LifeStateVector, candidates: Collection<FutureDeltaCandidate>): List<RankedFutureDelta> {
        val before = evaluator.evaluate(current).score
        return candidates.asSequence().filter { it.allowed }.map { candidate ->
            val after = evaluator.evaluate(current.shifted(candidate.stateDelta)).score
            RankedFutureDelta(candidate, before, after, after - before)
        }.sortedWith(
            compareByDescending<RankedFutureDelta> { it.expectedImprovement }
                .thenBy { it.candidate.resourceCost }
                .thenBy { it.candidate.id },
        ).toList()
    }
}
