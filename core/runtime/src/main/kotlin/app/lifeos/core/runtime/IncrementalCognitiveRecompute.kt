package app.lifeos.core.runtime

import app.lifeos.core.model.CognitiveDependency
import app.lifeos.core.model.PhotonId
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

    fun plan(delta: CognitiveRecomputeDelta, dependencies: Collection<CognitiveDependency>): CognitiveRecomputePlan {
        val affected = linkedSetOf<PhotonId>()
        val queue = ArrayDeque<PhotonId>()
        queue.add(delta.sourcePhotonId)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            dependencies.asSequence()
                .filter { it.sourcePhotonId == current }
                .sortedBy { it.stableFingerprint }
                .forEach { dependency ->
                    if (affected.add(dependency.targetPhotonId)) queue.add(dependency.targetPhotonId)
                }
        }

        return CognitiveRecomputePlan(
            affectedPhotonIds = affected,
            projectionStates = affected.associate { it.value to ProjectionValidity.STALE },
            escalateRegional = delta.magnitudeMicros >= regionalThresholdMicros,
            escalateGlobal = delta.magnitudeMicros >= globalThresholdMicros,
        )
    }
}
