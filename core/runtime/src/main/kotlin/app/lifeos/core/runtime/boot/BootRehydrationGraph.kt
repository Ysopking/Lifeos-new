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
        require(id !in dependsOn) { "Boot rehydration node must not depend on itself: $id" }
    }
}

data class BootRehydrationFailure(
    val nodeId: BootRehydrationNodeId,
    val criticality: BootCriticality,
    val reason: String,
    val dependencyFailure: Boolean,
) {
    init { require(reason.isNotBlank()) }
}

data class BootRehydrationReport(
    val completed: Set<BootRehydrationNodeId>,
    val requiredDegradedFailures: List<BootRehydrationFailure>,
    val optionalWarmFailures: List<BootRehydrationFailure>,
) {
    init {
        require(requiredDegradedFailures.all { it.criticality == BootCriticality.REQUIRED_DEGRADED })
        require(optionalWarmFailures.all { it.criticality == BootCriticality.OPTIONAL_WARM })
        require(requiredDegradedFailures == requiredDegradedFailures.sortedBy { it.nodeId.value })
        require(optionalWarmFailures == optionalWarmFailures.sortedBy { it.nodeId.value })
    }

    val degraded: Boolean
        get() = requiredDegradedFailures.isNotEmpty() || optionalWarmFailures.isNotEmpty()

    fun combinedWith(other: BootRehydrationReport): BootRehydrationReport =
        BootRehydrationReport(
            completed = (completed + other.completed).sortedBy { it.value }.toCollection(linkedSetOf()),
            requiredDegradedFailures = (requiredDegradedFailures + other.requiredDegradedFailures)
                .distinctBy { it.nodeId }
                .sortedBy { it.nodeId.value },
            optionalWarmFailures = (optionalWarmFailures + other.optionalWarmFailures)
                .distinctBy { it.nodeId }
                .sortedBy { it.nodeId.value },
        )
}

class BootRehydrationNodeFailure(
    val nodeId: BootRehydrationNodeId,
    val criticality: BootCriticality,
    cause: Throwable,
) : IllegalStateException(
    "Boot rehydration node failed: ${nodeId.value}: ${cause.message ?: cause::class.simpleName}",
    cause,
)

class BootRehydrationGraph(nodes: List<BootRehydrationNode>) {
    private val nodesById: Map<BootRehydrationNodeId, BootRehydrationNode>

    init {
        require(nodes.map { it.id }.distinct().size == nodes.size) {
            "Boot rehydration graph contains duplicate node ids"
        }
        nodesById = nodes.associateBy(BootRehydrationNode::id)
        val known = nodesById.keys
        nodes.forEach { node ->
            val unknown = node.dependsOn - known
            require(unknown.isEmpty()) {
                "Boot rehydration node ${node.id.value} has unknown dependencies: " +
                    unknown.map { it.value }.sorted().joinToString(",")
            }
        }
        validateAcyclic()
    }

    suspend fun rehydrate(): BootRehydrationReport {
        val critical = rehydrateCritical()
        return critical.combinedWith(rehydrateWarm(critical))
    }

    suspend fun rehydrateCritical(): BootRehydrationReport {
        val selected = nodesById.values
            .filter { it.criticality != BootCriticality.OPTIONAL_WARM }
            .mapTo(linkedSetOf()) { it.id }
        validatePhaseDependencies(selected, emptySet())
        return runSelection(selected, emptyMap())
    }

    suspend fun rehydrateWarm(criticalReport: BootRehydrationReport): BootRehydrationReport {
        require(criticalReport.optionalWarmFailures.isEmpty()) {
            "Critical rehydration report cannot contain warm failures"
        }
        val selected = nodesById.values
            .filter { it.criticality == BootCriticality.OPTIONAL_WARM }
            .mapTo(linkedSetOf()) { it.id }
        val initial = linkedMapOf<BootRehydrationNodeId, NodeResolution>()
        criticalReport.completed.sortedBy { it.value }.forEach { initial[it] = NodeResolution.COMPLETED }
        criticalReport.requiredDegradedFailures.sortedBy { it.nodeId.value }.forEach {
            initial[it.nodeId] = NodeResolution.FAILED
        }
        validatePhaseDependencies(selected, initial.keys)
        return runSelection(selected, initial)
    }

    private suspend fun runSelection(
        selected: Set<BootRehydrationNodeId>,
        initialResolutions: Map<BootRehydrationNodeId, NodeResolution>,
    ): BootRehydrationReport {
        if (selected.isEmpty()) {
            return BootRehydrationReport(emptySet(), emptyList(), emptyList())
        }

        val resolutions = linkedMapOf<BootRehydrationNodeId, NodeResolution>().apply {
            putAll(initialResolutions)
        }
        val completed = linkedSetOf<BootRehydrationNodeId>()
        val requiredFailures = mutableListOf<BootRehydrationFailure>()
        val warmFailures = mutableListOf<BootRehydrationFailure>()
        val remaining = nodesById.filterKeys { it in selected }.toMutableMap()

        fun record(failure: BootRehydrationFailure) {
            when (failure.criticality) {
                BootCriticality.SECURE_REQUIRED ->
                    error("Secure failures must be thrown before report aggregation")
                BootCriticality.REQUIRED_DEGRADED -> requiredFailures += failure
                BootCriticality.OPTIONAL_WARM -> warmFailures += failure
            }
        }

        while (remaining.isNotEmpty()) {
            val ready = remaining.values
                .filter { node -> node.dependsOn.all(resolutions::containsKey) }
                .sortedBy { it.id.value }
            check(ready.isNotEmpty()) { "Boot rehydration phase reached unresolved dependencies" }

            val blocked = ready.filter { node ->
                node.dependsOn.any { resolutions[it] == NodeResolution.FAILED }
            }
            blocked.forEach { node ->
                val deps = node.dependsOn
                    .filter { resolutions[it] == NodeResolution.FAILED }
                    .sortedBy { it.value }
                val cause = IllegalStateException(
                    "dependency-failed:" + deps.joinToString(",") { it.value }
                )
                if (node.criticality == BootCriticality.SECURE_REQUIRED) {
                    throw BootRehydrationNodeFailure(node.id, node.criticality, cause)
                }
                record(
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

            outcomes.filter {
                it.failure != null && it.node.criticality == BootCriticality.SECURE_REQUIRED
            }.minByOrNull { it.node.id.value }?.let { failed ->
                throw BootRehydrationNodeFailure(
                    failed.node.id,
                    failed.node.criticality,
                    requireNotNull(failed.failure),
                )
            }

            outcomes.sortedBy { it.node.id.value }.forEach { outcome ->
                remaining.remove(outcome.node.id)
                if (outcome.failure == null) {
                    completed += outcome.node.id
                    resolutions[outcome.node.id] = NodeResolution.COMPLETED
                } else {
                    record(
                        BootRehydrationFailure(
                            nodeId = outcome.node.id,
                            criticality = outcome.node.criticality,
                            reason = outcome.failure.message?.takeIf { it.isNotBlank() }
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
            requiredDegradedFailures = requiredFailures.sortedBy { it.nodeId.value },
            optionalWarmFailures = warmFailures.sortedBy { it.nodeId.value },
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
            check(ready.isNotEmpty()) { "Boot rehydration graph reached an impossible cyclic state" }
            layers += ready
            ready.forEach { id ->
                remaining.remove(id)
                completed += id
            }
        }
        return layers
    }

    private fun validatePhaseDependencies(
        selected: Set<BootRehydrationNodeId>,
        alreadyResolved: Set<BootRehydrationNodeId>,
    ) {
        nodesById.values.filter { it.id in selected }.forEach { node ->
            val unresolved = node.dependsOn - selected - alreadyResolved
            require(unresolved.isEmpty()) {
                "Boot rehydration phase for ${node.id.value} omits dependencies: " +
                    unresolved.map { it.value }.sorted().joinToString(",")
            }
        }
    }

    private fun validateAcyclic() {
        val visited = mutableSetOf<BootRehydrationNodeId>()
        val visiting = mutableSetOf<BootRehydrationNodeId>()
        fun visit(id: BootRehydrationNodeId) {
            if (id in visited) return
            require(id !in visiting) { "Boot rehydration graph contains a cycle at ${id.value}" }
            visiting += id
            nodesById.getValue(id).dependsOn.sortedBy { it.value }.forEach(::visit)
            visiting -= id
            visited += id
        }
        nodesById.keys.sortedBy { it.value }.forEach(::visit)
    }

    private enum class NodeResolution { COMPLETED, FAILED }
    private data class NodeOutcome(val node: BootRehydrationNode, val failure: Throwable?)
}
