package app.lifeos.core.field.world

import app.lifeos.core.field.StableFieldIds

data class WorldFieldNode(
    val id: WorldFieldNodeId,
    val target: WorldTargetRef,
    val intrinsic: WorldFieldVector = WorldFieldVector.EMPTY,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(attributes.keys.none { it.isBlank() }) {
            "World field node attribute keys must not be blank"
        }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-field-node/v1",
        id.value,
        target.fingerprint(),
        intrinsic.fingerprint(),
        *attributes.toSortedMap().flatMap { (key, value) -> listOf(key, value) }.toTypedArray(),
    )

    companion object {
        fun create(
            target: WorldTargetRef,
            intrinsic: WorldFieldVector = WorldFieldVector.EMPTY,
            attributes: Map<String, String> = emptyMap(),
        ): WorldFieldNode = WorldFieldNode(
            id = WorldFieldNodeId(
                worldId(
                    "world-node",
                    target.fingerprint(),
                )
            ),
            target = target,
            intrinsic = intrinsic,
            attributes = attributes,
        )
    }
}

data class WorldFieldEdge(
    val id: WorldFieldEdgeId,
    val sourceNodeId: WorldFieldNodeId,
    val targetNodeId: WorldFieldNodeId,
    val sourceDimension: WorldSignalDimension,
    val targetDimension: WorldSignalDimension,
    val coefficientId: WorldCoefficientId,
    val strength: Double,
    val explanation: String,
) {
    init {
        require(sourceNodeId != targetNodeId) { "World field edge cannot target its source" }
        require(strength.isFinite() && strength in 0.0..1.0) {
            "World field edge strength must be finite and in 0..1"
        }
        require(explanation.isNotBlank()) { "World field edge explanation must not be blank" }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-field-edge/v1",
        id.value,
        sourceNodeId.value,
        targetNodeId.value,
        sourceDimension.name,
        targetDimension.name,
        coefficientId.value,
        java.lang.Double.toHexString(strength),
        explanation,
    )

    companion object {
        fun create(
            sourceNodeId: WorldFieldNodeId,
            targetNodeId: WorldFieldNodeId,
            sourceDimension: WorldSignalDimension,
            targetDimension: WorldSignalDimension,
            coefficientId: WorldCoefficientId,
            strength: Double,
            explanation: String,
        ): WorldFieldEdge = WorldFieldEdge(
            id = WorldFieldEdgeId(
                worldId(
                    "world-edge",
                    sourceNodeId.value,
                    targetNodeId.value,
                    sourceDimension.name,
                    targetDimension.name,
                    coefficientId.value,
                    explanation,
                )
            ),
            sourceNodeId = sourceNodeId,
            targetNodeId = targetNodeId,
            sourceDimension = sourceDimension,
            targetDimension = targetDimension,
            coefficientId = coefficientId,
            strength = strength,
            explanation = explanation,
        )
    }
}

/**
 * Immutable deterministic field-of-fields topology. The graph only describes allowed typed
 * transfers; the numerical coupling is owned by a separate versioned [WorldEquationSpec].
 */
data class WorldFieldGraph(
    val nodes: List<WorldFieldNode>,
    val edges: List<WorldFieldEdge> = emptyList(),
) {
    init {
        require(nodes.isNotEmpty()) { "World field graph requires at least one node" }
        require(nodes.map { it.id }.distinct().size == nodes.size) {
            "World field graph node ids must be unique"
        }
        require(edges.map { it.id }.distinct().size == edges.size) {
            "World field graph edge ids must be unique"
        }
        val nodeIds = nodes.mapTo(mutableSetOf()) { it.id }
        require(edges.all { it.sourceNodeId in nodeIds && it.targetNodeId in nodeIds }) {
            "World field edges must reference graph nodes"
        }
    }

    private val nodeById = nodes.associateBy { it.id }
    private val stableNodesCache = nodes.sortedBy { it.id.value }
    private val stableEdgesCache = edges.sortedBy { it.id.value }
    private val incomingByNode = stableEdgesCache.groupBy { it.targetNodeId }
    private val outgoingByNode = stableEdgesCache.groupBy { it.sourceNodeId }

    fun node(id: WorldFieldNodeId): WorldFieldNode? = nodeById[id]

    fun stableNodes(): List<WorldFieldNode> = stableNodesCache

    fun stableEdges(): List<WorldFieldEdge> = stableEdgesCache

    fun incoming(nodeId: WorldFieldNodeId): List<WorldFieldEdge> =
        incomingByNode[nodeId].orEmpty()

    fun outgoing(nodeId: WorldFieldNodeId): List<WorldFieldEdge> =
        outgoingByNode[nodeId].orEmpty()

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-field-graph/v1",
        *stableNodes().map { it.fingerprint() }.toTypedArray(),
        *stableEdges().map { it.fingerprint() }.toTypedArray(),
    )
}
