package app.lifeos.core.runtime

import app.lifeos.core.model.CognitiveDependency
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.ProjectionValidity

/** A semantic/revision delta entering the incremental cognitive graph. */
data class CognitiveRecomputeDelta(
    val sourcePhotonId: PhotonId,
    val sourceRevision: Long,
    val magnitudeMicros: Long,
) {
    init {
        require(sourceRevision > 0)
        require(magnitudeMicros in 0..1_000_000L)
    }
}

data class CognitiveRecomputePlan(
    val affectedPhotonIds: Set<PhotonId>,
    val projectionStates: Map<String, ProjectionValidity>,
    val escalateRegional: Boolean,
    val escalateGlobal: Boolean,
    val affectedPhotonRefs: Set<PhotonRevisionRef> = emptySet(),
    val projectionStatesByRef: Map<PhotonRevisionRef, ProjectionValidity> = emptyMap(),
)

/**
 * Computes only the dependency closure affected by a delta. Escalation is a scheduler
 * decision based on propagated magnitude; this planner never performs a global world run.
 */
class IncrementalCognitiveRecomputePlanner(
    private val regionalThresholdMicros: Long = 250_000L,
    private val globalThresholdMicros: Long = 750_000L,
) {
    init {
        require(regionalThresholdMicros in 0..1_000_000L)
        require(globalThresholdMicros in regionalThresholdMicros..1_000_000L)
    }

    fun plan(
        delta: CognitiveRecomputeDelta,
        dependencies: Collection<CognitiveDependency>,
    ): CognitiveRecomputePlan = plan(delta, CognitiveDependencyIndex(dependencies))

    fun plan(
        delta: CognitiveRecomputeDelta,
        dependencyIndex: CognitiveDependencyIndex,
    ): CognitiveRecomputePlan {
        val propagated = dependencyIndex.propagate(
            source = PhotonRevisionRef(delta.sourcePhotonId, delta.sourceRevision),
            magnitudeMicros = delta.magnitudeMicros,
        )
        val refs = propagated.mapTo(linkedSetOf()) { it.target }
        val statesByRef = linkedMapOf<PhotonRevisionRef, ProjectionValidity>()
        propagated.forEach { value ->
            val validity = dependencyIndex.validity(value.reason)
            val previous = statesByRef[value.target]
            statesByRef[value.target] = when {
                previous == ProjectionValidity.INVALID -> previous
                validity == ProjectionValidity.INVALID -> validity
                else -> ProjectionValidity.STALE
            }
        }
        val states = statesByRef.entries
            .groupBy { it.key.photonId.value }
            .mapValues { (_, entries) ->
                if (entries.any { it.value == ProjectionValidity.INVALID }) {
                    ProjectionValidity.INVALID
                } else {
                    ProjectionValidity.STALE
                }
            }
            .toSortedMap()
        val maxMagnitude = propagated.maxOfOrNull { it.magnitudeMicros } ?: 0L

        return CognitiveRecomputePlan(
            affectedPhotonIds = refs.mapTo(linkedSetOf()) { it.photonId },
            projectionStates = states,
            escalateRegional = maxMagnitude >= regionalThresholdMicros,
            escalateGlobal = maxMagnitude >= globalThresholdMicros,
            affectedPhotonRefs = refs,
            projectionStatesByRef = statesByRef.toMap(),
        )
    }
}
