package app.lifeos.core.runtime.boot

import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope

@JvmInline
value class BootRehydrationNodeId(
    val value: String,
) {
    init {
        require(value.matches(Regex("[a-z0-9][a-z0-9-]{0,63}"))) {
            "Invalid boot rehydration node id: $value"
        }
    }

    override fun toString(): String = value
}

data class BootRehydrationNode(
    val id: BootRehydrationNodeId,
    val dependsOn: Set<BootRehydrationNodeId> = emptySet(),
    val action: suspend () -> Unit,
) {
    init {
        require(id !in dependsOn) {
            "Boot rehydration node cannot depend on itself: $id"
        }
    }
}

class BootRehydrationNodeException(
    val nodeId: BootRehydrationNodeId,
    cause: Throwable,
) : IllegalStateException(
    "Boot rehydration node failed: ${nodeId.value}: " +
        (cause.message ?: cause::class.simpleName.orEmpty()),
    cause,
)

class BootRehydrationGraph(
    nodes: List<BootRehydrationNode>,
    private val restoredState: suspend () -> RehydratedRuntimeState = {
        RehydratedRuntimeState()
    },
) : StateRehydrator {
    private val nodesById: Map<BootRehydrationNodeId, BootRehydrationNode> =
        validateAndIndex(nodes)

    override suspend fun rehydrate(): RehydratedRuntimeState {
        val pending = nodesById.toMutableMap()
        val completed = linkedSetOf<BootRehydrationNodeId>()

        while (pending.isNotEmpty()) {
            val ready = pending.values
                .asSequence()
                .filter { node -> node.dependsOn.all(completed::contains) }
                .sortedBy { it.id.value }
                .toList()

            check(ready.isNotEmpty()) {
                "Boot rehydration graph has no executable node"
            }

            val outcomes = supervisorScope {
                ready.map { node ->
                    node to async {
                        runCatching { node.action() }
                    }
                }.map { (node, deferred) ->
                    node to deferred.await()
                }
            }

            val failure = outcomes.firstOrNull { (_, result) -> result.isFailure }
            if (failure != null) {
                val (node, result) = failure
                throw BootRehydrationNodeException(
                    nodeId = node.id,
                    cause = requireNotNull(result.exceptionOrNull()),
                )
            }

            ready.forEach { node ->
                completed += node.id
                pending.remove(node.id)
            }
        }

        return restoredState()
    }

    private companion object {
        fun validateAndIndex(
            nodes: List<BootRehydrationNode>,
        ): Map<BootRehydrationNodeId, BootRehydrationNode> {
            val indexed = linkedMapOf<BootRehydrationNodeId, BootRehydrationNode>()
            nodes.forEach { node ->
                require(indexed.put(node.id, node) == null) {
                    "Duplicate boot rehydration node id: ${node.id}"
                }
            }

            val knownIds = indexed.keys
            indexed.values.forEach { node ->
                val unknown = node.dependsOn - knownIds
                require(unknown.isEmpty()) {
                    "Boot rehydration node ${node.id} has unknown dependencies: " +
                        unknown.sortedBy { it.value }.joinToString()
                }
            }

            val remaining = indexed.mapValues { (_, node) ->
                node.dependsOn.toMutableSet()
            }.toMutableMap()
            val resolved = linkedSetOf<BootRehydrationNodeId>()

            while (remaining.isNotEmpty()) {
                val ready = remaining
                    .filterValues { dependencies ->
                        dependencies.all(resolved::contains)
                    }
                    .keys
                    .sortedBy { it.value }

                require(ready.isNotEmpty()) {
                    "Boot rehydration graph contains a dependency cycle: " +
                        remaining.keys.sortedBy { it.value }.joinToString()
                }

                ready.forEach { id ->
                    resolved += id
                    remaining.remove(id)
                }
            }

            return indexed.toMap()
        }
    }
}
