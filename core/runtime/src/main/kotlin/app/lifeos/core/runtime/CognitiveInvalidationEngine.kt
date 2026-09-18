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
    val affectedByRef: Map<PhotonRevisionRef, ProjectionValidity> = emptyMap(),
    val recomputationPhotonRefs: Set<PhotonRevisionRef> = emptySet(),
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
        val affectedByRef = linkedMapOf<PhotonRevisionRef, ProjectionValidity>()
        propagated.forEach { value ->
            val next = dependencyIndex.validity(value.reason)
            affectedByRef[value.target] = when {
                affectedByRef[value.target] == ProjectionValidity.INVALID ->
                    ProjectionValidity.INVALID
                next == ProjectionValidity.INVALID ->
                    ProjectionValidity.INVALID
                else ->
                    ProjectionValidity.STALE
            }
        }
        val affected = affectedByRef.entries
            .groupBy { it.key.photonId.value }
            .mapValues { (_, entries) ->
                if (entries.any { it.value == ProjectionValidity.INVALID }) {
                    ProjectionValidity.INVALID
                } else {
                    ProjectionValidity.STALE
                }
            }
            .toSortedMap()
        val refs = affectedByRef.keys.toCollection(linkedSetOf())
        return InvalidationResult(
            affected = affected,
            recomputationPhotonIds = refs.mapTo(linkedSetOf()) { it.photonId },
            affectedByRef = affectedByRef.toMap(),
            recomputationPhotonRefs = refs,
        )
    }
}
