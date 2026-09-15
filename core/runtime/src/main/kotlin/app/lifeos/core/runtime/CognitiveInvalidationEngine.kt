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

/** Propagates revision deltas through dependency evidence without deleting historical state. */
class CognitiveInvalidationEngine {
    fun propagate(delta: CognitiveDelta, dependencies: Collection<CognitiveDependency>): InvalidationResult {
        val queue = ArrayDeque<PhotonId>()
        val visited = linkedSetOf<PhotonId>()
        queue.add(delta.changedPhotonId)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            dependencies.asSequence()
                .filter { it.sourcePhotonId == current }
                .sortedBy { it.stableFingerprint }
                .forEach { edge -> if (visited.add(edge.targetPhotonId)) queue.add(edge.targetPhotonId) }
        }
        return InvalidationResult(
            affected = visited.associate { it.value to ProjectionValidity.STALE },
            recomputationPhotonIds = visited,
        )
    }
}
