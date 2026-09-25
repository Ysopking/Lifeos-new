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
    val reason: String,
    val dependencyFailure: Boolean,
) {
    init {
        require(reason.isNotBlank())
    }
}

data class BootRehydrationReport(
    val completed: Set<BootRehydrationNodeId>,
    val requiredDegradedFailures: List<BootRehydrationFailure>,
    val optionalWarmFailures: List<BootRehydrationFailure>,
) {
    init {
        require(completed.toList() == completed.sortedBy { it.value }.toSet().toList())
        require(requiredDegradedFailures.all { it.criticality == BootCriticality.REQUIRED_DEGRADED })
        require(optionalWarmFailures.all { it.criticality == BootCriticality.OPTIONAL_WARM })
        require(requiredDegradedFailures == requiredDegradedFailures.sortedBy { it.nodeId.value })
        require(optionalWarmFailures == optionalWarmFailures.sortedBy { it.nodeId.value })
    }

    val degraded: Boolean
        get() = requiredDegradedFailures.isNotEmpty() || optionalWarmFailures.isNotEmpty()
}

class BootRehydrationNodeFailure(
    val nodeId: BootRehydrationNodeId,
    val criticality: BootCriticality,
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
                requiredDegradedFailures = emptyList(),
                optionalWarmFailures = emptyList(),
            )
        }

        val resolutions = linkedMapOf<BootRehydrationNodeId, NodeResolution>()
        val completed = linkedSetOf<BootRehydrationNodeId>()
        val requiredDegradedFailures = mutableListOf<BootRehydrationFailure>()
        val optionalWarmFailures = mutableListOf<BootRehydrationFailure>()
        val remaining = nodesById.toMutableMap()

        fun recordFailure(failure: BootRehydrationFailure) {
            when (failure.criticality) {
                BootCriticality.SECURE_REQUIRED ->
                    error("Secure failures must be thrown before report aggregation")
                BootCriticality.REQUIRED_DEGRADED ->
                    requiredDegradedFailures += failure
                BootCriticality.OPTIONAL_WARM ->
                    optionalWarmFailures += failure
            }
        }

        while (remaining.isNotEmpty()) {
            val ready = remaining.values
                .filter { node -> node.dependsOn.all(resolutions::containsKey) }
                .sortedBy { it.id.value }
            check(ready.isNotEmpty()) {
                "Boot rehydration graph reached an impossible cyclic state"
            }

            val blocked = ready.filter { node ->
                node.dependsOn.any { dependency ->
                    resolutions[dependency] == NodeResolution.FAILED
                }
            }
            blocked.forEach { node ->
                val failedDependencies = node.dependsOn
                    .filter { resolutions[it] == NodeResolution.FAILED }
                    .sortedBy { it.value }
                val cause = IllegalStateException(
                    "dependency-failed:" + failedDependencies.joinToString(",") { it.value }
                )
                if (node.criticality == BootCriticality.SECURE_REQUIRED) {
                    throw BootRehydrationNodeFailure(
                        nodeId = node.id,
                        criticality = node.criticality,
                        cause = cause,
                    )
                }
                recordFailure(
                    BootRehydrationFailure(
                        nodeId = node.id,
                        criticality = node.criticality,
                        reason = requireNotNull(cause.message),
                        dependencyFailure = true,
                    )
                )
                remaining.remove(node.id)
                resolutions[node.id] = NodeResolution.FAILED
            }

            val runnable = ready.filterNot { it in blocked }
            if (runnable.isEmpty()) continue

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

            outcomes
                .filter {
                    it.failure != null &&
                        it.node.criticality == BootCriticality.SECURE_REQUIRED
                }
                .minByOrNull { it.node.id.value }
                ?.let { failed ->
                    throw BootRehydrationNodeFailure(
                        nodeId = failed.node.id,
                        criticality = failed.node.criticality,
                        cause = requireNotNull(failed.failure),
                    )
                }

            outcomes.sortedBy { it.node.id.value }.forEach { outcome ->
                remaining.remove(outcome.node.id)
                if (outcome.failure == null) {
                    completed += outcome.node.id
                    resolutions[outcome.node.id] = NodeResolution.COMPLETED
                } else {
                    recordFailure(
                        BootRehydrationFailure(
                            nodeId = outcome.node.id,
                            criticality = outcome.node.criticality,
                            reason = outcome.failure.message
                                ?.takeIf { it.isNotBlank() }
                                ?: outcome.failure::class.simpleName
                                ?: "rehydration-failed",
                            dependencyFailure = false,
                        )
                    )
                    resolutions[outcome.node.id] = NodeResolution.FAILED
                }
            }
        }

        return BootRehydrationReport(
            completed = completed.sortedBy { it.value }.toCollection(linkedSetOf()),
            requiredDegradedFailures = requiredDegradedFailures.sortedBy { it.nodeId.value },
            optionalWarmFailures = optionalWarmFailures.sortedBy { it.nodeId.value },
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

    private enum class NodeResolution {
        COMPLETED,
        FAILED,
    }

    private data class NodeOutcome(
        val node: BootRehydrationNode,
        val failure: Throwable?,
    )
}
