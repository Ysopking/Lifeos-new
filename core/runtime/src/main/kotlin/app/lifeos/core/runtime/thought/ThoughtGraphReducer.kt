package app.lifeos.core.runtime.thought

import app.lifeos.core.field.StableFieldIds
import java.time.Instant

data class ThoughtGraphApplyReport(
    val state: ThoughtGraphState,
    val snapshot: ThoughtGraphSnapshot,
    val replayed: Boolean,
    val staleNodeVersionFingerprints: List<String>,
    val staleEdgeVersionFingerprints: List<String>,
) {
    init {
        require(staleNodeVersionFingerprints == staleNodeVersionFingerprints.distinct().sorted()) {
            "Stale node fingerprints must be unique and deterministically ordered"
        }
        require(staleEdgeVersionFingerprints == staleEdgeVersionFingerprints.distinct().sorted()) {
            "Stale edge fingerprints must be unique and deterministically ordered"
        }
    }
}

/**
 * Pure reducer for the append-only V3 cognitive graph history.
 *
 * Exact delta replay is idempotent. Older versions remain in history for provenance/replay but can
 * never displace a newer active version. Different variants at the same highest source revision
 * materialize as a first-class conflict and remove that subject from the active projection until a
 * later source revision resolves it.
 */
class ThoughtGraphReducer(
    private val now: () -> Instant = Instant::now,
) {
    fun apply(
        state: ThoughtGraphState,
        delta: ThoughtGraphDelta,
        capturedAt: Instant = now(),
    ): ThoughtGraphApplyReport {
        if (delta.id in state.appliedDeltaIds) {
            return ThoughtGraphApplyReport(
                state = state,
                snapshot = snapshot(state, capturedAt),
                replayed = true,
                staleNodeVersionFingerprints = emptyList(),
                staleEdgeVersionFingerprints = emptyList(),
            )
        }

        val priorNodeHighestRevision = state.nodeVersions
            .groupBy { it.id }
            .mapValues { (_, versions) -> versions.maxOf { it.sourceRevision } }
        val priorEdgeHighestRevision = state.edgeVersions
            .groupBy { it.id }
            .mapValues { (_, versions) -> versions.maxOf { it.sourceRevision } }

        val novelNodes = delta.nodeVersions
            .filterNot { candidate -> state.nodeVersions.any { it.fingerprint == candidate.fingerprint } }
        val novelEdges = delta.edgeVersions
            .filterNot { candidate -> state.edgeVersions.any { it.fingerprint == candidate.fingerprint } }

        val staleNodes = novelNodes
            .filter { version ->
                val highest = priorNodeHighestRevision[version.id]
                highest != null && version.sourceRevision < highest
            }
            .map { it.fingerprint }
            .distinct()
            .sorted()
        val staleEdges = novelEdges
            .filter { version ->
                val highest = priorEdgeHighestRevision[version.id]
                highest != null && version.sourceRevision < highest
            }
            .map { it.fingerprint }
            .distinct()
            .sorted()

        val appliedDeltaIds = (state.appliedDeltaIds + delta.id)
            .distinct()
            .sortedBy { it.value }
        val next = ThoughtGraphState(
            revision = appliedDeltaIds.size.toLong(),
            nodeVersions = (state.nodeVersions + novelNodes)
                .distinctBy { it.fingerprint }
                .sortedWith(nodeVersionOrdering()),
            edgeVersions = (state.edgeVersions + novelEdges)
                .distinctBy { it.fingerprint }
                .sortedWith(edgeVersionOrdering()),
            appliedDeltaIds = appliedDeltaIds,
        )
        return ThoughtGraphApplyReport(
            state = next,
            snapshot = snapshot(next, capturedAt),
            replayed = false,
            staleNodeVersionFingerprints = staleNodes,
            staleEdgeVersionFingerprints = staleEdges,
        )
    }

    fun replay(
        deltas: Iterable<ThoughtGraphDelta>,
        capturedAt: Instant = now(),
    ): ThoughtGraphApplyReport {
        var state = ThoughtGraphState()
        var lastReport = ThoughtGraphApplyReport(
            state = state,
            snapshot = snapshot(state, capturedAt),
            replayed = false,
            staleNodeVersionFingerprints = emptyList(),
            staleEdgeVersionFingerprints = emptyList(),
        )
        deltas.sortedBy { it.id.value }.forEach { delta ->
            lastReport = apply(state, delta, capturedAt)
            state = lastReport.state
        }
        return lastReport.copy(
            state = state,
            snapshot = snapshot(state, capturedAt),
        )
    }

    fun snapshot(
        state: ThoughtGraphState,
        capturedAt: Instant = now(),
    ): ThoughtGraphSnapshot {
        val nodeProjection = projectNodes(state.nodeVersions)
        val edgeProjection = projectEdges(state.edgeVersions)
        val conflicts = (nodeProjection.conflicts + edgeProjection.conflicts)
            .sortedWith(conflictOrdering())
        val activeNodeIds = nodeProjection.active.mapTo(mutableSetOf()) { it.id }
        val activeEdges = edgeProjection.active
            .filter { it.sourceNodeId in activeNodeIds && it.targetNodeId in activeNodeIds }
            .sortedBy { it.id.value }

        return ThoughtGraphSnapshot(
            revision = state.revision,
            activeNodes = nodeProjection.active.sortedBy { it.id.value },
            activeEdges = activeEdges,
            conflicts = conflicts,
            nodeHistoryCount = state.nodeVersions.size,
            edgeHistoryCount = state.edgeVersions.size,
            appliedDeltaIds = state.appliedDeltaIds,
            capturedAt = capturedAt,
            historyFingerprint = historyFingerprint(state),
        )
    }

    private fun projectNodes(
        versions: List<ThoughtGraphNodeVersion>,
    ): NodeProjection {
        val active = mutableListOf<ThoughtGraphNodeVersion>()
        val conflicts = mutableListOf<ThoughtGraphConflict>()
        versions.groupBy { it.id }
            .toSortedMap(compareBy<ThoughtGraphNodeId> { it.value })
            .forEach { (id, subjectVersions) ->
                val highestRevision = subjectVersions.maxOf { it.sourceRevision }
                val highestVariants = subjectVersions
                    .filter { it.sourceRevision == highestRevision }
                    .distinctBy { it.fingerprint }
                    .sortedBy { it.fingerprint }
                if (highestVariants.size == 1) {
                    active += highestVariants.single()
                } else {
                    conflicts += ThoughtGraphConflict.create(
                        subjectKind = ThoughtGraphConflictSubjectKind.NODE,
                        subjectId = id.value,
                        sourceRevision = highestRevision,
                        variantFingerprints = highestVariants.map { it.fingerprint },
                    )
                }
            }
        return NodeProjection(active, conflicts)
    }

    private fun projectEdges(
        versions: List<ThoughtGraphEdgeVersion>,
    ): EdgeProjection {
        val active = mutableListOf<ThoughtGraphEdgeVersion>()
        val conflicts = mutableListOf<ThoughtGraphConflict>()
        versions.groupBy { it.id }
            .toSortedMap(compareBy<ThoughtGraphEdgeId> { it.value })
            .forEach { (id, subjectVersions) ->
                val highestRevision = subjectVersions.maxOf { it.sourceRevision }
                val highestVariants = subjectVersions
                    .filter { it.sourceRevision == highestRevision }
                    .distinctBy { it.fingerprint }
                    .sortedBy { it.fingerprint }
                if (highestVariants.size == 1) {
                    active += highestVariants.single()
                } else {
                    conflicts += ThoughtGraphConflict.create(
                        subjectKind = ThoughtGraphConflictSubjectKind.EDGE,
                        subjectId = id.value,
                        sourceRevision = highestRevision,
                        variantFingerprints = highestVariants.map { it.fingerprint },
                    )
                }
            }
        return EdgeProjection(active, conflicts)
    }

    private fun historyFingerprint(state: ThoughtGraphState): String = StableFieldIds.fingerprint(
        "thought-graph/history/v1",
        *buildList {
            state.nodeVersions.sortedWith(nodeVersionOrdering()).forEach {
                add("node:${it.fingerprint}")
            }
            state.edgeVersions.sortedWith(edgeVersionOrdering()).forEach {
                add("edge:${it.fingerprint}")
            }
            state.appliedDeltaIds.sortedBy { it.value }.forEach {
                add("delta:${it.value}")
            }
        }.toTypedArray(),
    )

    private data class NodeProjection(
        val active: List<ThoughtGraphNodeVersion>,
        val conflicts: List<ThoughtGraphConflict>,
    )

    private data class EdgeProjection(
        val active: List<ThoughtGraphEdgeVersion>,
        val conflicts: List<ThoughtGraphConflict>,
    )
}
