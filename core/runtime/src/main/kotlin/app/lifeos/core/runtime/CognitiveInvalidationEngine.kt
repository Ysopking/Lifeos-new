package app.lifeos.core.runtime

import app.lifeos.core.model.*

private const val MICROS = 1_000_000L

data class CognitiveDelta(
    val changedPhotonId: PhotonId,
    val fromRevision: Long,
    val toRevision: Long,
    val magnitudeMicros: Long = MICROS,
    val traceId: CausalTraceId,
) {
    init {
        require(fromRevision > 0 && toRevision > fromRevision)
        require(magnitudeMicros >= 0L)
    }

    val changedRef: PhotonRevisionRef get() = PhotonRevisionRef(changedPhotonId, toRevision)
}

enum class CognitivePropagationScope { LOCAL, REGIONAL, GLOBAL }

data class CognitivePropagationContext(
    val temporalWeightMicros: Long = MICROS,
    val relevanceMicros: Long = MICROS,
    val confidenceMicros: Long = MICROS,
    val activeMatterWeightMicros: Long = MICROS,
    val uncertaintyWeightMicros: Long = MICROS,
) {
    init {
        require(temporalWeightMicros in 0L..MICROS)
        require(relevanceMicros in 0L..MICROS)
        require(confidenceMicros in 0L..MICROS)
        require(activeMatterWeightMicros in 0L..MICROS)
        require(uncertaintyWeightMicros in 0L..MICROS)
    }
}

data class InvalidationResult(
    val affected: Map<String, ProjectionValidity>,
    val recomputationPhotonIds: Set<PhotonId>,
    val propagatedDeltas: List<PropagatedCognitiveDelta>,
    val propagationEnergyMicros: Long,
    val scope: CognitivePropagationScope,
)

/**
 * Propagates revision deltas through dependency evidence without deleting historical state.
 *
 * The propagation is deterministic and fixed-point based: edge coupling, decay, temporal
 * relevance and confidence attenuate the incoming magnitude. Polarity is retained as an
 * influence on magnitude while dependency kind remains the semantic reason. A target is
 * revisited only when a stronger propagated magnitude reaches it, preventing cycles from
 * producing unbounded work.
 */
class CognitiveInvalidationEngine(
    private val regionalThresholdMicros: Long = 2_000_000L,
    private val globalThresholdMicros: Long = 8_000_000L,
) {
    init {
        require(regionalThresholdMicros >= 0L)
        require(globalThresholdMicros >= regionalThresholdMicros)
    }

    fun propagate(
        delta: CognitiveDelta,
        dependencies: Collection<CognitiveDependency>,
        context: CognitivePropagationContext = CognitivePropagationContext(),
    ): InvalidationResult {
        val outgoing = dependencies
            .groupBy { it.sourcePhotonId }
            .mapValues { (_, edges) -> edges.sortedBy { it.stableFingerprint } }

        val queue = ArrayDeque<Pair<PhotonId, Long>>()
        val strongestByTarget = linkedMapOf<PhotonRevisionRef, Long>()
        val propagated = mutableListOf<PropagatedCognitiveDelta>()
        queue.add(delta.changedPhotonId to delta.magnitudeMicros)

        while (queue.isNotEmpty()) {
            val (sourceId, sourceMagnitude) = queue.removeFirst()
            outgoing[sourceId].orEmpty().forEach { edge ->
                val magnitude = propagateMagnitude(sourceMagnitude, edge, context)
                if (magnitude <= 0L) return@forEach

                val target = edge.targetRef
                val previous = strongestByTarget[target] ?: -1L
                if (magnitude <= previous) return@forEach

                strongestByTarget[target] = magnitude
                propagated += PropagatedCognitiveDelta(
                    target = target,
                    magnitudeMicros = magnitude,
                    reason = edge.kind,
                    traceId = delta.traceId,
                )
                queue.add(edge.targetPhotonId to magnitude)
            }
        }

        val affectedIds = strongestByTarget.keys.mapTo(linkedSetOf()) { it.photonId }
        val connectivityMicros = if (affectedIds.isEmpty()) 0L else {
            (affectedIds.size.toLong() * MICROS).coerceAtMost(10L * MICROS)
        }
        val totalMagnitude = strongestByTarget.values.fold(0L) { acc, value -> saturatingAdd(acc, value) }
        val weightedMagnitude = scale(scale(totalMagnitude, context.activeMatterWeightMicros), context.uncertaintyWeightMicros)
        val propagationEnergy = saturatingAdd(weightedMagnitude, connectivityMicros)
        val scope = when {
            propagationEnergy >= globalThresholdMicros -> CognitivePropagationScope.GLOBAL
            propagationEnergy >= regionalThresholdMicros -> CognitivePropagationScope.REGIONAL
            else -> CognitivePropagationScope.LOCAL
        }

        return InvalidationResult(
            affected = affectedIds.associate { it.value to ProjectionValidity.STALE },
            recomputationPhotonIds = affectedIds,
            propagatedDeltas = propagated.toList(),
            propagationEnergyMicros = propagationEnergy,
            scope = scope,
        )
    }

    private fun propagateMagnitude(
        sourceMagnitude: Long,
        edge: CognitiveDependency,
        context: CognitivePropagationContext,
    ): Long {
        var value = sourceMagnitude
        value = scale(value, edge.couplingMicros)
        value = scale(value, kotlin.math.abs(edge.polarityMicros))
        value = scale(value, edge.decayMicros)
        value = scale(value, context.temporalWeightMicros)
        value = scale(value, context.relevanceMicros)
        value = scale(value, context.confidenceMicros)
        return value
    }

    private fun scale(value: Long, factorMicros: Long): Long {
        if (value == 0L || factorMicros == 0L) return 0L
        val quotient = value / MICROS
        val remainder = value % MICROS
        return saturatingAdd(
            saturatingMultiply(quotient, factorMicros),
            saturatingMultiply(remainder, factorMicros) / MICROS,
        )
    }

    private fun saturatingMultiply(a: Long, b: Long): Long {
        if (a == 0L || b == 0L) return 0L
        return if (a > Long.MAX_VALUE / b) Long.MAX_VALUE else a * b
    }

    private fun saturatingAdd(a: Long, b: Long): Long =
        if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
}
