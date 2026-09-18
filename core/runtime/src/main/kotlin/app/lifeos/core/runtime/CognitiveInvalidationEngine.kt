package app.lifeos.core.runtime

import app.lifeos.core.model.*

data class CognitiveDelta(
    val changedPhotonId: PhotonId,
    val fromRevision: Long,
    val toRevision: Long,
) { init { require(fromRevision > 0 && toRevision > fromRevision) } }

data class InvalidationResult(
    val affected: Map<String, ProjectionValidity>,
    val recomputationPhotonIds: Set<PhotonId>,
)

/** Uses the same revision-aware dependency index as recompute; no second BFS topology exists. */
class CognitiveInvalidationEngine {
    fun propagate(
        delta: CognitiveDelta,
        dependencies: Collection<CognitiveDependency>,
    ): InvalidationResult = propagate(delta, CognitiveDependencyIndex(dependencies))

    fun propagate(
        delta: CognitiveDelta,
        dependencyIndex: CognitiveDependencyIndex,
    ): InvalidationResult {
        val propagated = dependencyIndex.propagate(
            source = PhotonRevisionRef(delta.changedPhotonId, delta.fromRevision),
            magnitudeMicros = 1_000_000L,
        )
        val affected = linkedMapOf<String, ProjectionValidity>()
        propagated.forEach { value ->
            val next = dependencyIndex.validity(value.reason)
            val key = value.target.photonId.value
            affected[key] = when {
                affected[key] == ProjectionValidity.INVALID -> ProjectionValidity.INVALID
                next == ProjectionValidity.INVALID -> ProjectionValidity.INVALID
                else -> ProjectionValidity.STALE
            }
        }
        return InvalidationResult(
            affected = affected,
            recomputationPhotonIds = propagated.mapTo(linkedSetOf()) { it.target.photonId },
        )
    }
}
