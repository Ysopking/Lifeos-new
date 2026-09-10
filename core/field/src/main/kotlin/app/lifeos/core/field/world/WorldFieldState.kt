package app.lifeos.core.field.world

import app.lifeos.core.field.StableFieldIds

data class WorldFieldState(
    val graphFingerprint: String,
    val equationFingerprint: String,
    val generation: Int,
    val vectors: Map<WorldFieldNodeId, WorldFieldVector>,
) {
    init {
        require(graphFingerprint.isNotBlank()) { "World state graph fingerprint must not be blank" }
        require(equationFingerprint.isNotBlank()) { "World state equation fingerprint must not be blank" }
        require(generation >= 0) { "World state generation must not be negative" }
    }

    operator fun get(nodeId: WorldFieldNodeId): WorldFieldVector? = vectors[nodeId]

    fun stableVectors(): List<Pair<WorldFieldNodeId, WorldFieldVector>> = vectors.entries
        .sortedBy { it.key.value }
        .map { it.key to it.value }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-field-state/v1",
        graphFingerprint,
        equationFingerprint,
        generation.toString(),
        *stableVectors().flatMap { (nodeId, vector) ->
            listOf(nodeId.value, vector.fingerprint())
        }.toTypedArray(),
    )

    fun next(nextVectors: Map<WorldFieldNodeId, WorldFieldVector>): WorldFieldState {
        require(nextVectors.keys == vectors.keys) {
            "World state generation cannot add or remove graph nodes"
        }
        return copy(
            generation = generation + 1,
            vectors = nextVectors.toSortedMap(compareBy { it.value }),
        )
    }

    companion object {
        fun initial(
            graph: WorldFieldGraph,
            equationFingerprint: String,
        ): WorldFieldState = WorldFieldState(
            graphFingerprint = graph.fingerprint(),
            equationFingerprint = equationFingerprint,
            generation = 0,
            vectors = graph.stableNodes().associate { node -> node.id to node.intrinsic }
                .toSortedMap(compareBy { it.value }),
        )
    }
}
