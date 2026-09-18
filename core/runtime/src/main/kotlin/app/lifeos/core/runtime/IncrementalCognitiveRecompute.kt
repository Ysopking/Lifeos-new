package app.lifeos.core.runtime

import app.lifeos.core.model.CognitiveDependency
import app.lifeos.core.model.GraphActivityClass
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.ProjectionValidity
import app.lifeos.core.model.PropagatedCognitiveDelta

/** A semantic/revision delta entering the incremental cognitive graph. */
data class CognitiveRecomputeDelta(
    val sourcePhotonId: PhotonId,
    val sourceRevision: Long,
    val magnitudeMicros: Long,
) {
    init {
        require(sourceRevision > 0)
        require(magnitudeMicros in 0..MICROS)
    }

    val sourceRef: PhotonRevisionRef get() = PhotonRevisionRef(sourcePhotonId, sourceRevision)

    private companion object {
        const val MICROS = 1_000_000L
    }
}

data class CognitiveRecomputePlan(
    val affectedPhotonIds: Set<PhotonId>,
    val projectionStates: Map<String, ProjectionValidity>,
    val escalateRegional: Boolean,
    val escalateGlobal: Boolean,
    val affectedRevisions: Set<PhotonRevisionRef> = emptySet(),
    val propagatedDeltas: List<PropagatedCognitiveDelta> = emptyList(),
    val propagationEnergyMicros: Long = 0L,
)

/**
 * Revision-exact adjacency index. Building it is O(E); every propagation step afterwards is O(out-degree)
 * rather than rescanning the complete dependency collection for every affected photon.
 */
class CognitiveDependencyIndex private constructor(
    private val outgoingBySource: Map<PhotonRevisionRef, List<CognitiveDependency>>,
) {
    fun outgoing(source: PhotonRevisionRef): List<CognitiveDependency> = outgoingBySource[source].orEmpty()

    fun connectivity(ref: PhotonRevisionRef): Int = outgoingBySource[ref]?.size ?: 0

    companion object {
        fun build(dependencies: Collection<CognitiveDependency>): CognitiveDependencyIndex {
            val indexed = dependencies
                .groupBy { it.sourceRef }
                .mapValues { (_, edges) -> edges.sortedBy { it.stableFingerprint } }
            return CognitiveDependencyIndex(indexed)
        }
    }
}

/**
 * Computes only the revision-exact dependency closure affected by a delta. Edge propagation uses fixed-point
 * integer weights so replay is deterministic. Regional/global escalation is based on total propagated energy,
 * never on the origin magnitude alone.
 */
class IncrementalCognitiveRecomputePlanner(
    private val regionalThresholdMicros: Long = 250_000L,
    private val globalThresholdMicros: Long = 750_000L,
) {
    init {
        require(regionalThresholdMicros >= 0L)
        require(globalThresholdMicros >= regionalThresholdMicros)
    }

    fun plan(
        delta: CognitiveRecomputeDelta,
        dependencies: Collection<CognitiveDependency>,
        activityClasses: Map<PhotonRevisionRef, GraphActivityClass> = emptyMap(),
        uncertaintyWeightsMicros: Map<PhotonRevisionRef, Long> = emptyMap(),
    ): CognitiveRecomputePlan = plan(
        delta = delta,
        index = CognitiveDependencyIndex.build(dependencies),
        activityClasses = activityClasses,
        uncertaintyWeightsMicros = uncertaintyWeightsMicros,
    )

    fun plan(
        delta: CognitiveRecomputeDelta,
        index: CognitiveDependencyIndex,
        activityClasses: Map<PhotonRevisionRef, GraphActivityClass> = emptyMap(),
        uncertaintyWeightsMicros: Map<PhotonRevisionRef, Long> = emptyMap(),
    ): CognitiveRecomputePlan {
        uncertaintyWeightsMicros.values.forEach { require(it in 0L..MICROS) }

        val origin = delta.sourceRef
        val bestMagnitude = linkedMapOf(origin to delta.magnitudeMicros)
        val bestPropagation = linkedMapOf<PhotonRevisionRef, PropagatedCognitiveDelta>()
        val queue = ArrayDeque<PhotonRevisionRef>()
        queue.add(origin)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val incomingMagnitude = bestMagnitude.getValue(current)

            index.outgoing(current).forEach { dependency ->
                val propagatedMagnitude = propagateMagnitude(incomingMagnitude, dependency)
                if (propagatedMagnitude <= 0L) return@forEach

                val target = dependency.targetRef
                val previous = bestMagnitude[target] ?: -1L
                if (propagatedMagnitude <= previous) return@forEach

                bestMagnitude[target] = propagatedMagnitude
                bestPropagation[target] = PropagatedCognitiveDelta(
                    target = target,
                    magnitudeMicros = propagatedMagnitude,
                    reason = dependency.kind,
                    traceId = dependency.traceId,
                )
                queue.add(target)
            }
        }

        val affectedRevisions = bestMagnitude.keys
            .asSequence()
            .filter { it != origin }
            .sortedBy { it.stableKey }
            .toCollection(linkedSetOf())

        val propagated = affectedRevisions.mapNotNull(bestPropagation::get)
        val propagationEnergy = affectedRevisions.fold(0L) { total, ref ->
            val magnitude = bestMagnitude.getValue(ref)
            val connectivity = index.connectivity(ref).coerceAtLeast(1).toLong()
            val activityWeight = activityWeightMicros(activityClasses[ref] ?: GraphActivityClass.HOT)
            val uncertaintyWeight = uncertaintyWeightsMicros[ref] ?: MICROS
            val weighted = multiplyMicros(multiplyMicros(magnitude, activityWeight), uncertaintyWeight)
            saturatingAdd(total, saturatingMultiply(weighted, connectivity))
        }

        val affectedPhotonIds = affectedRevisions.mapTo(linkedSetOf()) { it.photonId }
        return CognitiveRecomputePlan(
            affectedPhotonIds = affectedPhotonIds,
            projectionStates = affectedRevisions.associate { it.stableKey to ProjectionValidity.STALE },
            escalateRegional = propagationEnergy >= regionalThresholdMicros,
            escalateGlobal = propagationEnergy >= globalThresholdMicros,
            affectedRevisions = affectedRevisions,
            propagatedDeltas = propagated,
            propagationEnergyMicros = propagationEnergy,
        )
    }

    private fun propagateMagnitude(input: Long, dependency: CognitiveDependency): Long {
        val polarityStrength = if (dependency.polarityMicros < 0L) -dependency.polarityMicros else dependency.polarityMicros
        return multiplyMicros(
            multiplyMicros(
                multiplyMicros(input, dependency.couplingMicros),
                dependency.decayMicros,
            ),
            polarityStrength,
        )
    }

    private fun activityWeightMicros(activityClass: GraphActivityClass): Long = when (activityClass) {
        GraphActivityClass.HOT -> MICROS
        GraphActivityClass.WARM -> 650_000L
        GraphActivityClass.COLD -> 250_000L
    }

    private fun multiplyMicros(left: Long, right: Long): Long {
        require(left >= 0L && right in 0L..MICROS)
        if (left == 0L || right == 0L) return 0L
        return (left * right) / MICROS
    }

    private fun saturatingMultiply(value: Long, factor: Long): Long {
        require(value >= 0L && factor >= 0L)
        if (value == 0L || factor == 0L) return 0L
        return if (value > Long.MAX_VALUE / factor) Long.MAX_VALUE else value * factor
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private companion object {
        const val MICROS = 1_000_000L
    }
}
