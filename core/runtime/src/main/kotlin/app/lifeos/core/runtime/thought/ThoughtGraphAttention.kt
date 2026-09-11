package app.lifeos.core.runtime.thought

import app.lifeos.core.field.StableFieldIds
import java.time.Instant
import java.util.ArrayDeque

data class ThoughtGraphAttentionPolicy(
    val maxNodes: Int = 128,
    val maxEdges: Int = 256,
    val maxConflicts: Int = 64,
    val goalDepth: Int = 2,
) {
    init {
        require(maxNodes > 0) { "Thought graph attention must allow at least one node" }
        require(maxEdges >= 0) { "Thought graph attention edge limit must not be negative" }
        require(maxConflicts >= 0) { "Thought graph attention conflict limit must not be negative" }
        require(goalDepth >= 0) { "Thought graph attention goal depth must not be negative" }
    }
}

enum class ThoughtGraphAttentionReason {
    GOAL,
    GOAL_NEIGHBOR,
    HYPOTHESIS,
    UNRESOLVED,
    COMPETING,
    CONFLICT_CONTEXT,
    TEMPORALLY_VALID,
    HIGH_CONFIDENCE,
    HIGH_AUTHORITY,
}

data class ThoughtGraphAttentionEntry(
    val nodeId: ThoughtGraphNodeId,
    val score: Double,
    val reasons: List<ThoughtGraphAttentionReason>,
) {
    init {
        require(score.isFinite() && score >= 0.0) { "Thought graph attention score must be finite and non-negative" }
        require(reasons == reasons.distinct().sortedBy { it.name }) {
            "Thought graph attention reasons must be unique and deterministically ordered"
        }
    }
}

data class ThoughtGraphWorkingSet(
    val sourceSnapshotId: String,
    val sourceRevision: Long,
    val sourceHistoryFingerprint: String,
    val asOf: Instant,
    val policy: ThoughtGraphAttentionPolicy,
    val entries: List<ThoughtGraphAttentionEntry>,
    val nodes: List<ThoughtGraphNodeVersion>,
    val edges: List<ThoughtGraphEdgeVersion>,
    val conflicts: List<ThoughtGraphConflict>,
) {
    init {
        require(sourceSnapshotId.isNotBlank()) { "Working-set source snapshot id must not be blank" }
        require(sourceRevision >= 0L) { "Working-set source revision must not be negative" }
        require(sourceHistoryFingerprint.isNotBlank()) { "Working-set history fingerprint must not be blank" }
        require(entries.size <= policy.maxNodes) { "Working-set node limit exceeded" }
        require(edges.size <= policy.maxEdges) { "Working-set edge limit exceeded" }
        require(conflicts.size <= policy.maxConflicts) { "Working-set conflict limit exceeded" }
        require(entries == entries.sortedWith(attentionEntryOrdering())) {
            "Working-set attention entries must be deterministically ranked"
        }
        require(nodes.map { it.id } == entries.map { it.nodeId }) {
            "Working-set node order must exactly match attention ranking"
        }
        require(nodes.map { it.id }.distinct().size == nodes.size) {
            "Working-set nodes must be unique"
        }
        require(edges == edges.sortedBy { it.id.value }) {
            "Working-set edges must be deterministically ordered"
        }
        require(conflicts == conflicts.sortedWith(conflictOrdering())) {
            "Working-set conflicts must be deterministically ordered"
        }
    }

    val fingerprint: String = StableFieldIds.fingerprint(
        "thought-graph/working-set/v1",
        sourceSnapshotId,
        sourceRevision.toString(),
        sourceHistoryFingerprint,
        asOf.toString(),
        policy.maxNodes.toString(),
        policy.maxEdges.toString(),
        policy.maxConflicts.toString(),
        policy.goalDepth.toString(),
        *buildList {
            entries.forEach { entry ->
                add(
                    "entry:${entry.nodeId.value}:${java.lang.Double.toHexString(entry.score)}:" +
                        entry.reasons.joinToString(",") { it.name }
                )
            }
            nodes.forEach { add("node:${it.fingerprint}") }
            edges.forEach { add("edge:${it.fingerprint}") }
            conflicts.forEach { add("conflict:${it.id}") }
        }.toTypedArray(),
    )
}

/**
 * Pure bounded attention projection over an immutable ThoughtGraphSnapshot.
 *
 * The persistent graph remains the sole source of truth. This class only ranks and selects a
 * reconstructible working set from explicit graph state plus an explicit evaluation instant.
 */
class ThoughtGraphAttentionProjector {
    fun project(
        snapshot: ThoughtGraphSnapshot,
        policy: ThoughtGraphAttentionPolicy = ThoughtGraphAttentionPolicy(),
        asOf: Instant = snapshot.capturedAt,
    ): ThoughtGraphWorkingSet {
        val nodesById = snapshot.activeNodes.associateBy { it.id }
        val adjacency = buildAdjacency(snapshot.activeNodes, snapshot.activeEdges)
        val goalDistances = goalDistances(
            goalIds = snapshot.activeNodes
                .filter(::isGoalNode)
                .map { it.id }
                .sortedBy { it.value },
            adjacency = adjacency,
            maxDepth = policy.goalDepth,
        )
        val conflictContext = conflictContextNodeIds(snapshot)

        val entries = snapshot.activeNodes
            .map { node ->
                attentionEntry(
                    node = node,
                    goalDistance = goalDistances[node.id],
                    conflictContext = node.id in conflictContext,
                    asOf = asOf,
                )
            }
            .sortedWith(attentionEntryOrdering())
            .take(policy.maxNodes)
        val selectedNodeIds = entries.mapTo(linkedSetOf()) { it.nodeId }
        val selectedNodes = entries.map { nodesById.getValue(it.nodeId) }

        val selectedEdges = snapshot.activeEdges
            .asSequence()
            .filter { it.sourceNodeId in selectedNodeIds && it.targetNodeId in selectedNodeIds }
            .sortedWith(edgeAttentionOrdering(asOf))
            .take(policy.maxEdges)
            .sortedBy { it.id.value }
            .toList()

        val selectedConflicts = snapshot.conflicts
            .sortedWith(
                compareByDescending<ThoughtGraphConflict> { it.sourceRevision }
                    .thenBy { it.subjectKind.name }
                    .thenBy { it.subjectId }
                    .thenBy { it.id },
            )
            .take(policy.maxConflicts)
            .sortedWith(conflictOrdering())

        return ThoughtGraphWorkingSet(
            sourceSnapshotId = snapshot.snapshotId,
            sourceRevision = snapshot.revision,
            sourceHistoryFingerprint = snapshot.historyFingerprint,
            asOf = asOf,
            policy = policy,
            entries = entries,
            nodes = selectedNodes,
            edges = selectedEdges,
            conflicts = selectedConflicts,
        )
    }

    private fun attentionEntry(
        node: ThoughtGraphNodeVersion,
        goalDistance: Int?,
        conflictContext: Boolean,
        asOf: Instant,
    ): ThoughtGraphAttentionEntry {
        val reasons = mutableSetOf<ThoughtGraphAttentionReason>()
        val goalNode = isGoalNode(node)
        var score = when {
            goalNode -> 4.0
            node.kind == ThoughtGraphNodeKind.CONFLICT -> 3.0
            node.kind == ThoughtGraphNodeKind.HYPOTHESIS -> 2.0
            node.kind == ThoughtGraphNodeKind.EVIDENCE -> 1.0
            else -> 0.75
        }

        if (goalNode) reasons += ThoughtGraphAttentionReason.GOAL
        if (node.kind == ThoughtGraphNodeKind.HYPOTHESIS) reasons += ThoughtGraphAttentionReason.HYPOTHESIS

        when (node.attributes["state"]) {
            "UNRESOLVED" -> {
                score += 1.50
                reasons += ThoughtGraphAttentionReason.UNRESOLVED
            }
            "COMPETING" -> {
                score += 1.00
                reasons += ThoughtGraphAttentionReason.COMPETING
            }
        }

        if (goalDistance != null) {
            score += when (goalDistance) {
                0 -> 2.0
                1 -> 1.0
                else -> 0.5
            }
            if (goalDistance > 0) reasons += ThoughtGraphAttentionReason.GOAL_NEIGHBOR
        }

        if (conflictContext) {
            score += 0.70
            reasons += ThoughtGraphAttentionReason.CONFLICT_CONTEXT
        }

        if (node.validity.contains(asOf)) {
            score += 0.30
            reasons += ThoughtGraphAttentionReason.TEMPORALLY_VALID
        }

        score += node.confidence * 0.50
        score += node.authority * 0.40
        if (node.confidence >= 0.75) reasons += ThoughtGraphAttentionReason.HIGH_CONFIDENCE
        if (node.authority >= 0.75) reasons += ThoughtGraphAttentionReason.HIGH_AUTHORITY

        return ThoughtGraphAttentionEntry(
            nodeId = node.id,
            score = score,
            reasons = reasons.sortedBy { it.name },
        )
    }

    private fun isGoalNode(node: ThoughtGraphNodeVersion): Boolean =
        node.kind == ThoughtGraphNodeKind.GOAL ||
            (node.kind == ThoughtGraphNodeKind.PHOTON && node.attributes["mimeType"] == GOAL_PHOTON_MIME_TYPE)

    private fun buildAdjacency(
        nodes: List<ThoughtGraphNodeVersion>,
        edges: List<ThoughtGraphEdgeVersion>,
    ): Map<ThoughtGraphNodeId, Set<ThoughtGraphNodeId>> {
        val activeIds = nodes.mapTo(mutableSetOf()) { it.id }
        val adjacency = nodes.associate { it.id to linkedSetOf<ThoughtGraphNodeId>() }.toMutableMap()
        edges.forEach { edge ->
            if (edge.sourceNodeId !in activeIds || edge.targetNodeId !in activeIds) return@forEach
            adjacency.getValue(edge.sourceNodeId).add(edge.targetNodeId)
            adjacency.getValue(edge.targetNodeId).add(edge.sourceNodeId)
        }
        return adjacency.mapValues { (_, neighbors) -> neighbors.sortedBy { it.value }.toSet() }
    }

    private fun goalDistances(
        goalIds: List<ThoughtGraphNodeId>,
        adjacency: Map<ThoughtGraphNodeId, Set<ThoughtGraphNodeId>>,
        maxDepth: Int,
    ): Map<ThoughtGraphNodeId, Int> {
        if (goalIds.isEmpty()) return emptyMap()
        val distances = mutableMapOf<ThoughtGraphNodeId, Int>()
        val queue = ArrayDeque<ThoughtGraphNodeId>()
        goalIds.forEach { goalId ->
            if (goalId !in distances) {
                distances[goalId] = 0
                queue.addLast(goalId)
            }
        }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val distance = distances.getValue(current)
            if (distance >= maxDepth) continue
            adjacency[current].orEmpty().sortedBy { it.value }.forEach { neighbor ->
                if (neighbor !in distances) {
                    distances[neighbor] = distance + 1
                    queue.addLast(neighbor)
                }
            }
        }
        return distances.toMap()
    }

    private fun conflictContextNodeIds(snapshot: ThoughtGraphSnapshot): Set<ThoughtGraphNodeId> {
        val ids = mutableSetOf<ThoughtGraphNodeId>()
        snapshot.activeEdges.forEach { edge ->
            if (
                edge.kind == ThoughtGraphEdgeKind.CONTRADICTS ||
                edge.kind == ThoughtGraphEdgeKind.COMPETES_WITH ||
                edge.kind == ThoughtGraphEdgeKind.CONFLICTS_WITH ||
                edge.kind == ThoughtGraphEdgeKind.EVIDENCE_AGAINST
            ) {
                ids += edge.sourceNodeId
                ids += edge.targetNodeId
            }
        }
        snapshot.conflicts
            .filter { it.subjectKind == ThoughtGraphConflictSubjectKind.NODE }
            .forEach { conflict ->
                snapshot.activeNodes
                    .firstOrNull { it.id.value == conflict.subjectId }
                    ?.let { ids += it.id }
            }
        return ids
    }

    private fun edgeAttentionOrdering(asOf: Instant): Comparator<ThoughtGraphEdgeVersion> =
        compareByDescending<ThoughtGraphEdgeVersion> { edgePriority(it, asOf) }
            .thenBy { it.id.value }

    private fun edgePriority(edge: ThoughtGraphEdgeVersion, asOf: Instant): Double {
        val kindPriority = when (edge.kind) {
            ThoughtGraphEdgeKind.TARGETS_GOAL -> 5.0
            ThoughtGraphEdgeKind.CONTRADICTS,
            ThoughtGraphEdgeKind.COMPETES_WITH,
            ThoughtGraphEdgeKind.CONFLICTS_WITH,
            ThoughtGraphEdgeKind.EVIDENCE_AGAINST -> 4.0
            ThoughtGraphEdgeKind.SUPPORTS,
            ThoughtGraphEdgeKind.EVIDENCE_FOR -> 3.0
            ThoughtGraphEdgeKind.DERIVED_FROM,
            ThoughtGraphEdgeKind.REFERENCES,
            ThoughtGraphEdgeKind.TRANSFORMS -> 2.0
            ThoughtGraphEdgeKind.TEMPORALLY_SUPERSEDES -> 1.0
        }
        return kindPriority + edge.confidence + edge.authority + if (edge.validity.contains(asOf)) 0.25 else 0.0
    }

    private companion object {
        const val GOAL_PHOTON_MIME_TYPE = "application/vnd.lifeos.goal+text"
    }
}

internal fun attentionEntryOrdering(): Comparator<ThoughtGraphAttentionEntry> =
    compareByDescending<ThoughtGraphAttentionEntry> { it.score }
        .thenBy { it.nodeId.value }
