package app.lifeos.core.runtime

import app.lifeos.core.model.CognitiveDependency
import app.lifeos.core.model.CognitiveDependencyKind
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.PropagatedCognitiveDelta
import app.lifeos.core.model.ProjectionValidity

/**
 * Immutable revision-aware dependency topology shared by recompute and invalidation.
 */
class CognitiveDependencyIndex(
    dependencies: Collection<CognitiveDependency>,
) {
    val outgoingBySourceRef: Map<PhotonRevisionRef, List<CognitiveDependency>> =
        dependencies.groupBy { it.sourceRef }.mapValues { (_, values) ->
            values.sortedBy { it.stableFingerprint }
        }

    val incomingByTargetRef: Map<PhotonRevisionRef, List<CognitiveDependency>> =
        dependencies.groupBy { it.targetRef }.mapValues { (_, values) ->
            values.sortedBy { it.stableFingerprint }
        }

    fun outgoing(ref: PhotonRevisionRef): List<CognitiveDependency> =
        outgoingBySourceRef[ref].orEmpty()

    fun incoming(ref: PhotonRevisionRef): List<CognitiveDependency> =
        incomingByTargetRef[ref].orEmpty()

    fun validity(kind: CognitiveDependencyKind): ProjectionValidity = when (kind) {
        CognitiveDependencyKind.INVALIDATES,
        CognitiveDependencyKind.SUPERSEDES -> ProjectionValidity.INVALID

        CognitiveDependencyKind.DERIVED_FROM,
        CognitiveDependencyKind.DEPENDS_ON,
        CognitiveDependencyKind.SUPPORTS,
        CognitiveDependencyKind.CONTRADICTS -> ProjectionValidity.STALE
    }

    /**
     * Propagates the maximum energy reaching every target revision. A weaker path never replaces a
     * stronger one, which bounds cyclic graphs while preserving the strongest dependency signal.
     */
    fun propagate(
        source: PhotonRevisionRef,
        magnitudeMicros: Long,
    ): List<PropagatedCognitiveDelta> {
        require(magnitudeMicros in 0L..SCALE)
        if (magnitudeMicros == 0L) return emptyList()

        data class Pending(
            val ref: PhotonRevisionRef,
            val magnitude: Long,
        )

        val queue = ArrayDeque<Pending>()
        val strongest = mutableMapOf<PhotonRevisionRef, Long>()
        val reason = mutableMapOf<PhotonRevisionRef, CognitiveDependency>()
        queue.add(Pending(source, magnitudeMicros))

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            outgoing(current.ref).forEach { edge ->
                val nextMagnitude = propagatedMagnitude(current.magnitude, edge)
                if (nextMagnitude <= 0L) return@forEach
                val previous = strongest[edge.targetRef] ?: -1L
                if (nextMagnitude <= previous) return@forEach
                strongest[edge.targetRef] = nextMagnitude
                reason[edge.targetRef] = edge
                queue.add(Pending(edge.targetRef, nextMagnitude))
            }
        }

        return strongest.entries
            .sortedWith(compareBy({ it.key.photonId.value }, { it.key.revision }))
            .map { (target, magnitude) ->
                val edge = reason.getValue(target)
                PropagatedCognitiveDelta(
                    target = target,
                    magnitudeMicros = magnitude,
                    reason = edge.kind,
                    traceId = edge.traceId,
                )
            }
    }

    private fun propagatedMagnitude(
        currentMagnitude: Long,
        edge: CognitiveDependency,
    ): Long {
        var value = multiplyMicros(currentMagnitude, edge.couplingMicros)
        value = multiplyMicros(value, kotlin.math.abs(edge.polarityMicros))
        value = multiplyMicros(value, edge.decayMicros)
        return value.coerceIn(0L, SCALE)
    }

    private fun multiplyMicros(left: Long, right: Long): Long {
        require(left in 0L..SCALE && right in 0L..SCALE)
        if (left == 0L || right == 0L) return 0L
        val product = Math.multiplyExact(left, right)
        return (product / SCALE).coerceAtMost(SCALE)
    }

    private companion object {
        const val SCALE = 1_000_000L
    }
}
