package app.lifeos.core.runtime.boot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

@JvmInline
value class BootRehydrationNodeId(val value: String) {
    init {
        require(value.matches(Regex("[a-z0-9][a-z0-9-]{0,63}"))) {
            "Invalid boot rehydration node id: $value"
        }
    }

    override fun toString(): String = value
}

class BootRehydrationNode(
    val id: BootRehydrationNodeId,
    val dependsOn: Set<BootRehydrationNodeId> = emptySet(),
    val action: suspend () -> Unit,
) {
    init {
        require(id !in dependsOn) {
            "Boot rehydration node must not depend on itself: $id"
        }
    }
}

class BootRehydrationNodeFailure(
    val nodeId: BootRehydrationNodeId,
    cause: Throwable,
) : IllegalStateException(
    "Boot rehydration node failed: ${nodeId.value}: ${cause.message ?: cause::class.simpleName}",
    cause,
)

class BootRehydrationGraph(
    nodes: List<BootRehydrationNode>,
) {
    private val nodesById: Map<BootRehydrationNodeId, BootRehydrationNode>

    init {
        require(nodes.map { it.id }.distinct().size == nodes.size) {
            "Boot rehydration graph contains duplicate node ids"
        }
        nodesById = nodes.associateBy(BootRehydrationNode::id)
        val knownIds = nodesById.keys
        nodes.forEach { node ->
            val unknown = node.dependsOn - knownIds
            require(unknown.isEmpty()) {
                "Boot rehydration node ${node.id.value} has unknown dependencies: " +
                    unknown.map { it.value }.sorted().joinToString(",")
            }
        }
        validateAcyclic()
    }

    suspend fun rehydrate() {
        if (nodesById.isEmpty()) return

        val completed = linkedSetOf<BootRehydrationNodeId>()
        val remaining = nodesById.toMutableMap()

        while (remaining.isNotEmpty()) {
            val ready = remaining.values
                .filter { node -> node.dependsOn.all(completed::contains) }
                .sortedBy { it.id.value }
            check(ready.isNotEmpty()) {
                "Boot rehydration graph reached an impossible cyclic state"
            }

            val outcomes = coroutineScope {
                ready.map { node ->
                    async {
                        try {
                            node.action()
                            NodeOutcome(node.id, null)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            NodeOutcome(node.id, error)
                        }
                    }
                }.awaitAll()
            }

            outcomes
                .filter { it.failure != null }
                .minByOrNull { it.id.value }
                ?.let { failed ->
                    throw BootRehydrationNodeFailure(
                        failed.id,
                        requireNotNull(failed.failure),
                    )
                }

            ready.forEach { node ->
                remaining.remove(node.id)
                completed += node.id
            }
        }
    }

    fun topologicalLayers(): List<List<BootRehydrationNodeId>> {
        if (nodesById.isEmpty()) return emptyList()

        val completed = linkedSetOf<BootRehydrationNodeId>()
        val remaining = nodesById.toMutableMap()
        val layers = mutableListOf<List<BootRehydrationNodeId>>()

        while (remaining.isNotEmpty()) {
            val ready = remaining.values
                .filter { node -> node.dependsOn.all(completed::contains) }
                .map { it.id }
                .sortedBy { it.value }
            check(ready.isNotEmpty()) {
                "Boot rehydration graph reached an impossible cyclic state"
            }
            layers += ready
            ready.forEach { id ->
                remaining.remove(id)
                completed += id
            }
        }
        return layers
    }

    private fun validateAcyclic() {
        val visited = mutableSetOf<BootRehydrationNodeId>()
        val visiting = mutableSetOf<BootRehydrationNodeId>()

        fun visit(id: BootRehydrationNodeId) {
            if (id in visited) return
            require(id !in visiting) {
                "Boot rehydration graph contains a cycle at ${id.value}"
            }
            visiting += id
            nodesById.getValue(id).dependsOn
                .sortedBy { it.value }
                .forEach(::visit)
            visiting -= id
            visited += id
        }

        nodesById.keys.sortedBy { it.value }.forEach(::visit)
    }

    private data class NodeOutcome(
        val id: BootRehydrationNodeId,
        val failure: Throwable?,
    )
}
