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

enum class BootCriticality {
    SECURE_REQUIRED,
    REQUIRED_DEGRADED,
    OPTIONAL_WARM,
}

class BootRehydrationNode(
    val id: BootRehydrationNodeId,
    val dependsOn: Set<BootRehydrationNodeId> = emptySet(),
    val criticality: BootCriticality = BootCriticality.SECURE_REQUIRED,
    val action: suspend () -> Unit,
) {
    init {
        require(id !in dependsOn) {
            "Boot rehydration node must not depend on itself: $id"
        }
    }
}

data class BootRehydrationFailure(
    val nodeId: BootRehydrationNodeId,
    val criticality: BootCriticality,
    val message: String,
) {
    init {
        require(message.isNotBlank())
    }
}

data class BootRehydrationReport(
    val completed: Set<BootRehydrationNodeId>,
    val degraded: List<BootRehydrationFailure>,
    val warmFailures: List<BootRehydrationFailure>,
    val failedSecure: List<BootRehydrationFailure>,
) {
    init {
        require(degraded == degraded.sortedBy { it.nodeId.value })
        require(warmFailures == warmFailures.sortedBy { it.nodeId.value })
        require(failedSecure == failedSecure.sortedBy { it.nodeId.value })
        require(degraded.all { it.criticality == BootCriticality.REQUIRED_DEGRADED })
        require(warmFailures.all { it.criticality == BootCriticality.OPTIONAL_WARM })
        require(failedSecure.all { it.criticality == BootCriticality.SECURE_REQUIRED })
    }

    val degradedNodeIds: List<BootRehydrationNodeId>
        get() = degraded.map { it.nodeId }

    val warmFailureNodeIds: List<BootRehydrationNodeId>
        get() = warmFailures.map { it.nodeId }
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

    suspend fun rehydrate(): BootRehydrationReport {
        if (nodesById.isEmpty()) {
            return BootRehydrationReport(
                completed = emptySet(),
                degraded = emptyList(),
                warmFailures = emptyList(),
                failedSecure = emptyList(),
            )
        }

        val completed = linkedSetOf<BootRehydrationNodeId>()
        val degraded = mutableListOf<BootRehydrationFailure>()
        val warmFailures = mutableListOf<BootRehydrationFailure>()
        val failedSecure = mutableListOf<BootRehydrationFailure>()

        fun recordFailure(node: BootRehydrationNode, message: String) {
            val failure = BootRehydrationFailure(
                nodeId = node.id,
                criticality = node.criticality,
                message = message,
            )
            when (node.criticality) {
                BootCriticality.SECURE_REQUIRED -> failedSecure += failure
                BootCriticality.REQUIRED_DEGRADED -> degraded += failure
                BootCriticality.OPTIONAL_WARM -> warmFailures += failure
            }
        }

        for (layer in topologicalLayers()) {
            val nodes = layer.map(nodesById::getValue)
            val runnable = mutableListOf<BootRehydrationNode>()

            nodes.forEach { node ->
                val blockedBy = node.dependsOn.filterNot(completed::contains).sortedBy { it.value }
                if (blockedBy.isEmpty()) {
                    runnable += node
                } else {
                    recordFailure(
                        node,
                        "blocked-by:" + blockedBy.joinToString(",") { it.value },
                    )
                }
            }

            val outcomes = coroutineScope {
                runnable.map { node ->
                    async {
                        try {
                            node.action()
                            NodeOutcome(node, null)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            NodeOutcome(node, error)
                        }
                    }
                }.awaitAll()
            }

            outcomes.sortedBy { it.node.id.value }.forEach { outcome ->
                val error = outcome.failure
                if (error == null) {
                    completed += outcome.node.id
                } else {
                    recordFailure(
                        outcome.node,
                        error.message ?: error::class.simpleName ?: "rehydration-failed",
                    )
                }
            }

            val secureFailure = failedSecure.minByOrNull { it.nodeId.value }
            if (secureFailure != null) {
                throw BootRehydrationNodeFailure(
                    secureFailure.nodeId,
                    IllegalStateException(secureFailure.message),
                )
            }
        }

        return BootRehydrationReport(
            completed = completed.toSet(),
            degraded = degraded.sortedBy { it.nodeId.value },
            warmFailures = warmFailures.sortedBy { it.nodeId.value },
            failedSecure = failedSecure.sortedBy { it.nodeId.value },
        )
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
        val node: BootRehydrationNode,
        val failure: Throwable?,
    )
}
