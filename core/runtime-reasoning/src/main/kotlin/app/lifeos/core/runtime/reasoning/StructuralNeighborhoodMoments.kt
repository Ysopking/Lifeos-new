package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.thought.ThoughtMatrixSnapshot
import app.lifeos.core.runtime.thought.ThoughtRelation
import java.util.ArrayDeque

data class StructuralAnalysisBudget(
    val maxDepth: Int = 4,
    val maxVisitedNodes: Int = 2_048,
    val maxVisitedEdges: Int = 8_192,
) {
    init {
        require(maxDepth in 1..8)
        require(maxVisitedNodes in 1..100_000)
        require(maxVisitedEdges in 1..500_000)
    }
}

data class StructuralLayerSignature(
    val depth: Int,
    val pathClassFingerprints: List<String>,
    val opaqueBoundaryCount: Int,
    val fingerprint: String,
) {
    init {
        require(depth > 0)
        require(pathClassFingerprints == pathClassFingerprints.distinct().sorted())
        require(opaqueBoundaryCount >= 0)
    }
}

data class StructuralNeighborhoodSignature(
    val sourcePhotonId: PhotonId,
    val layers: List<StructuralLayerSignature>,
    val budgetExhausted: Boolean,
    val fingerprint: String,
) {
    init {
        require(layers == layers.sortedBy { it.depth })
        require(layers.map { it.depth }.distinct().size == layers.size)
    }

    fun layerFingerprint(depth: Int): String? =
        layers.firstOrNull { it.depth == depth }?.fingerprint
}

class StructuralNeighborhoodMomentAnalyzer {
    fun analyze(
        snapshot: ThoughtMatrixSnapshot,
        sourcePhotonId: PhotonId,
        budget: StructuralAnalysisBudget = StructuralAnalysisBudget(),
    ): StructuralNeighborhoodSignature {
        val nodeByPhoton = snapshot.nodes
            .groupBy { it.photonId }
            .mapValues { (_, nodes) ->
                nodes.sortedBy { it.id.value }.first()
            }
        val outgoing = snapshot.relations.groupBy { it.sourcePhotonId }
        val incoming = snapshot.relations.groupBy { it.targetPhotonId }

        var visitedNodes = 1
        var visitedEdges = 0
        var budgetExhausted = false
        val seen = linkedSetOf(sourcePhotonId)
        var frontier = linkedSetOf(sourcePhotonId)
        val layers = mutableListOf<StructuralLayerSignature>()

        for (depth in 1..budget.maxDepth) {
            if (frontier.isEmpty()) break
            val classes = mutableListOf<String>()
            var opaque = 0
            val next = linkedSetOf<PhotonId>()

            frontier.sortedBy { it.value }.forEach { current ->
                val edges = buildList {
                    outgoing[current].orEmpty().forEach { add(Direction.OUT to it) }
                    incoming[current].orEmpty().forEach { add(Direction.IN to it) }
                }.sortedWith(
                    compareBy<Pair<Direction, ThoughtRelation>> { it.first.name }
                        .thenBy { it.second.type.name }
                        .thenBy { it.second.sourcePhotonId.value }
                        .thenBy { it.second.targetPhotonId.value }
                        .thenBy { it.second.id }
                )

                for ((direction, relation) in edges) {
                    if (visitedEdges >= budget.maxVisitedEdges) {
                        budgetExhausted = true
                        break
                    }
                    visitedEdges += 1
                    val neighbor = if (direction == Direction.OUT) {
                        relation.targetPhotonId
                    } else {
                        relation.sourcePhotonId
                    }
                    val targetNode = nodeByPhoton[neighbor]
                    if (targetNode == null) {
                        opaque += 1
                    }
                    classes += StableFieldIds.fingerprint(
                        "meta-structural-path/v1",
                        depth.toString(),
                        direction.name,
                        relation.type.name,
                        java.lang.Double.toHexString(relation.weight),
                        targetNode?.fieldDomainId?.value ?: "OPAQUE_BOUNDARY",
                        targetNode?.semanticKey ?: "OPAQUE_BOUNDARY",
                    )
                    if (neighbor !in seen) {
                        if (visitedNodes >= budget.maxVisitedNodes) {
                            budgetExhausted = true
                        } else {
                            visitedNodes += 1
                            seen += neighbor
                            next += neighbor
                        }
                    }
                }
                if (budgetExhausted && visitedEdges >= budget.maxVisitedEdges) {
                    return@forEach
                }
            }

            val canonical = classes.distinct().sorted()
            layers += StructuralLayerSignature(
                depth = depth,
                pathClassFingerprints = canonical,
                opaqueBoundaryCount = opaque,
                fingerprint = StableFieldIds.fingerprint(
                    "meta-structural-layer/v1",
                    sourcePhotonId.value,
                    depth.toString(),
                    opaque.toString(),
                    *canonical.toTypedArray(),
                ),
            )
            if (budgetExhausted) break
            frontier = next
        }

        return StructuralNeighborhoodSignature(
            sourcePhotonId = sourcePhotonId,
            layers = layers,
            budgetExhausted = budgetExhausted,
            fingerprint = StableFieldIds.fingerprint(
                "meta-structural-neighborhood/v1",
                sourcePhotonId.value,
                budgetExhausted.toString(),
                *layers.map { it.fingerprint }.toTypedArray(),
            ),
        )
    }

    private enum class Direction {
        IN,
        OUT,
    }
}
