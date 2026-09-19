package app.lifeos.core.runtime.execution

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.task.TaskPriority
import app.lifeos.core.runtime.resource.HardwareExecutionClass
import java.time.Instant

@JvmInline
value class WorkGraphId(val value: String) {
    init {
        require(value.startsWith(PREFIX))
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}")))
    }
    override fun toString(): String = value

    companion object {
        const val PREFIX = "work-graph:"

        fun create(
            namespace: String,
            semanticInputFingerprint: String,
            strategyFingerprint: String,
            equationVersion: String,
            moduleSnapshotFingerprint: String,
        ): WorkGraphId {
            require(namespace.isNotBlank())
            require(semanticInputFingerprint.isNotBlank())
            require(strategyFingerprint.isNotBlank())
            require(equationVersion.isNotBlank())
            require(moduleSnapshotFingerprint.isNotBlank())
            return WorkGraphId(
                PREFIX + StableFieldIds.fingerprint(
                    "work-graph-id/v1",
                    namespace,
                    semanticInputFingerprint,
                    strategyFingerprint,
                    equationVersion,
                    moduleSnapshotFingerprint,
                )
            )
        }
    }
}

@JvmInline
value class WorkNodeId(val value: String) {
    init {
        require(value.startsWith(PREFIX))
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}")))
    }
    override fun toString(): String = value

    companion object {
        const val PREFIX = "work-node:"

        fun create(
            graphId: WorkGraphId,
            parentNodeId: WorkNodeId?,
            operationKind: String,
            executorImplementationFingerprint: String,
            inputFingerprint: String,
            partition: Int,
        ): WorkNodeId {
            require(operationKind.isNotBlank())
            require(executorImplementationFingerprint.isNotBlank())
            require(inputFingerprint.isNotBlank())
            require(partition >= 0)
            return WorkNodeId(
                PREFIX + StableFieldIds.fingerprint(
                    "work-node-id/v1",
                    graphId.value,
                    parentNodeId?.value.orEmpty(),
                    operationKind,
                    executorImplementationFingerprint,
                    inputFingerprint,
                    partition.toString(),
                )
            )
        }
    }
}

@JvmInline
value class WorkResultId(val value: String) {
    init {
        require(value.startsWith(PREFIX))
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}")))
    }
    override fun toString(): String = value

    companion object {
        const val PREFIX = "work-result:"

        fun create(
            graphId: WorkGraphId,
            nodeId: WorkNodeId,
            resultFingerprint: String,
        ): WorkResultId {
            require(resultFingerprint.isNotBlank())
            return WorkResultId(
                PREFIX + StableFieldIds.fingerprint(
                    "work-result-id/v1",
                    graphId.value,
                    nodeId.value,
                    resultFingerprint,
                )
            )
        }
    }
}

enum class WorkGraphState {
    ACTIVE,
    COMPLETED,
    FAILED,
    CANCELLED,
}

enum class WorkNodeState {
    PENDING,
    QUEUED,
    RUNNING,
    COMPLETED,
    REUSED,
    FAILED,
    CANCELLED,
}

enum class WorkPurity {
    PURE,
    IMPURE,
}

data class WorkInputRef(
    val sourceId: String,
    val revision: Long,
    val fingerprint: String,
) {
    init {
        require(sourceId.isNotBlank())
        require(revision > 0L)
        require(fingerprint.isNotBlank())
    }

    fun canonicalKey(): String = "$sourceId@$revision:$fingerprint"
}

data class WorkResultRef(
    val id: WorkResultId,
    val nodeId: WorkNodeId,
    val fingerprint: String,
) {
    init { require(fingerprint.isNotBlank()) }

    companion object {
        fun create(
            graphId: WorkGraphId,
            nodeId: WorkNodeId,
            fingerprint: String,
        ): WorkResultRef = WorkResultRef(
            id = WorkResultId.create(graphId, nodeId, fingerprint),
            nodeId = nodeId,
            fingerprint = fingerprint,
        )
    }
}

data class WorkCostEstimate(
    val workUnits: Long,
    val schedulingOverheadUnits: Long = 1L,
    val memoryBytes: Long = 0L,
    val ioBytes: Long = 0L,
) {
    init {
        require(workUnits >= 0L)
        require(schedulingOverheadUnits >= 0L)
        require(memoryBytes >= 0L)
        require(ioBytes >= 0L)
    }
}

data class WorkNode(
    val graphId: WorkGraphId,
    val id: WorkNodeId,
    val parentNodeId: WorkNodeId?,
    val dependencies: Set<WorkNodeId>,
    val operationKind: String,
    val executorId: String,
    val executorImplementationFingerprint: String,
    val inputs: List<WorkInputRef>,
    val inputFingerprint: String,
    val estimatedCost: WorkCostEstimate,
    val executionClass: HardwareExecutionClass,
    val priority: TaskPriority,
    val purity: WorkPurity,
    val checkpointable: Boolean,
    val reducerId: String,
    val partition: Int,
    val state: WorkNodeState = WorkNodeState.PENDING,
    val taskId: String? = null,
    val result: WorkResultRef? = null,
) {
    init {
        require(operationKind.isNotBlank())
        require(executorId.isNotBlank())
        require(executorImplementationFingerprint.isNotBlank())
        require(inputs.isNotEmpty())
        require(inputFingerprint == fingerprintInputs(inputs)) { "Work node input fingerprint mismatch" }
        require(reducerId.isNotBlank())
        require(partition >= 0)
        require(id !in dependencies) { "Work node cannot depend on itself" }
        require(parentNodeId != id) { "Work node cannot parent itself" }
        require(
            id == WorkNodeId.create(
                graphId = graphId,
                parentNodeId = parentNodeId,
                operationKind = operationKind,
                executorImplementationFingerprint = executorImplementationFingerprint,
                inputFingerprint = inputFingerprint,
                partition = partition,
            )
        ) { "Work node id/content mismatch" }
        when (state) {
            WorkNodeState.COMPLETED,
            WorkNodeState.REUSED -> require(result != null) {
                "Terminal successful work node requires a result"
            }
            else -> require(result == null) {
                "Non-successful work node must not expose a result"
            }
        }
        if (result != null) {
            require(result.nodeId == id) { "Work result belongs to another node" }
        }
        if (state == WorkNodeState.REUSED) {
            require(taskId == null) { "Reused work must not bind an execution task" }
        }
        require(taskId == null || taskId.isNotBlank())
    }

    val pure: Boolean get() = purity == WorkPurity.PURE
    val resultFingerprint: String? get() = result?.fingerprint
    val successful: Boolean get() = state == WorkNodeState.COMPLETED || state == WorkNodeState.REUSED

    fun withQueuedTask(taskId: String): WorkNode {
        require(state == WorkNodeState.PENDING)
        require(taskId.isNotBlank())
        return copy(state = WorkNodeState.QUEUED, taskId = taskId)
    }

    fun withResult(result: WorkResultRef, reused: Boolean): WorkNode {
        require(result.nodeId == id)
        return copy(
            state = if (reused) WorkNodeState.REUSED else WorkNodeState.COMPLETED,
            taskId = if (reused) null else taskId,
            result = result,
        )
    }

    companion object {
        fun create(
            graphId: WorkGraphId,
            parentNodeId: WorkNodeId? = null,
            dependencies: Set<WorkNodeId> = emptySet(),
            operationKind: String,
            executorId: String,
            executorImplementationFingerprint: String,
            inputs: List<WorkInputRef>,
            estimatedCost: WorkCostEstimate,
            executionClass: HardwareExecutionClass,
            priority: TaskPriority = TaskPriority.NORMAL,
            purity: WorkPurity = WorkPurity.PURE,
            checkpointable: Boolean = true,
            reducerId: String = "identity",
            partition: Int = 0,
        ): WorkNode {
            val inputFingerprint = fingerprintInputs(inputs)
            return WorkNode(
                graphId = graphId,
                id = WorkNodeId.create(
                    graphId,
                    parentNodeId,
                    operationKind,
                    executorImplementationFingerprint,
                    inputFingerprint,
                    partition,
                ),
                parentNodeId = parentNodeId,
                dependencies = dependencies,
                operationKind = operationKind,
                executorId = executorId,
                executorImplementationFingerprint = executorImplementationFingerprint,
                inputs = inputs.toList(),
                inputFingerprint = inputFingerprint,
                estimatedCost = estimatedCost,
                executionClass = executionClass,
                priority = priority,
                purity = purity,
                checkpointable = checkpointable,
                reducerId = reducerId,
                partition = partition,
            )
        }

        fun fingerprintInputs(inputs: List<WorkInputRef>): String {
            require(inputs.isNotEmpty())
            return StableFieldIds.fingerprint(
                "work-inputs/v1",
                *inputs
                    .sortedWith(compareBy<WorkInputRef>({ it.sourceId }, { it.revision }, { it.fingerprint }))
                    .map(WorkInputRef::canonicalKey)
                    .toTypedArray(),
            )
        }
    }
}

data class WorkGraph(
    val id: WorkGraphId,
    val revision: Long,
    val state: WorkGraphState,
    val nodes: List<WorkNode>,
    val terminalNodeId: WorkNodeId,
    val createdAt: Instant,
    val updatedAt: Instant,
    val finalResultFingerprint: String? = null,
) {
    init {
        require(revision > 0L)
        require(nodes.isNotEmpty())
        require(nodes.map { it.id }.distinct().size == nodes.size) { "Work graph node ids must be unique" }
        require(nodes.all { it.graphId == id }) { "Work graph contains a node from another graph" }
        val ids = nodes.mapTo(linkedSetOf()) { it.id }
        require(terminalNodeId in ids) { "Terminal work node is missing from graph" }
        nodes.forEach { node ->
            require(ids.containsAll(node.dependencies)) { "Work node dependency is missing from graph" }
            require(node.parentNodeId == null || node.parentNodeId in ids) {
                "Work node parent is missing from graph"
            }
        }
        require(!updatedAt.isBefore(createdAt))
        requireAcyclic(nodes)
        when (state) {
            WorkGraphState.COMPLETED -> {
                require(nodes.all(WorkNode::successful))
                val terminal = nodes.single { it.id == terminalNodeId }
                require(finalResultFingerprint == terminal.resultFingerprint) {
                    "Completed graph must bind the terminal result fingerprint"
                }
                require(!finalResultFingerprint.isNullOrBlank())
            }
            WorkGraphState.ACTIVE -> require(finalResultFingerprint == null)
            WorkGraphState.FAILED,
            WorkGraphState.CANCELLED -> Unit
        }
    }

    fun readyNodes(): List<WorkNode> {
        val successful = nodes.filter(WorkNode::successful).mapTo(hashSetOf()) { it.id }
        return nodes
            .asSequence()
            .filter { it.state == WorkNodeState.PENDING }
            .filter { successful.containsAll(it.dependencies) }
            .sortedBy { it.id.value }
            .toList()
    }

    fun node(id: WorkNodeId): WorkNode? = nodes.firstOrNull { it.id == id }

    fun contentFingerprint(): String = StableFieldIds.fingerprint(
        "work-graph-content/v1",
        id.value,
        revision.toString(),
        state.name,
        terminalNodeId.value,
        createdAt.toString(),
        updatedAt.toString(),
        finalResultFingerprint.orEmpty(),
        *nodes.sortedBy { it.id.value }.flatMap { node ->
            buildList {
                add(node.id.value)
                add(node.parentNodeId?.value.orEmpty())
                add(node.operationKind)
                add(node.executorId)
                add(node.executorImplementationFingerprint)
                add(node.inputFingerprint)
                add(node.executionClass.name)
                add(node.priority.name)
                add(node.purity.name)
                add(node.checkpointable.toString())
                add(node.reducerId)
                add(node.partition.toString())
                add(node.state.name)
                add(node.taskId.orEmpty())
                add(node.resultFingerprint.orEmpty())
                node.dependencies.sortedBy { it.value }.forEach { add("dep:${it.value}") }
                node.inputs.sortedBy(WorkInputRef::canonicalKey).forEach { add("input:${it.canonicalKey()}") }
            }
        }.toTypedArray(),
    )

    private fun requireAcyclic(nodes: List<WorkNode>) {
        val byId = nodes.associateBy { it.id }
        val visiting = hashSetOf<WorkNodeId>()
        val visited = hashSetOf<WorkNodeId>()

        fun visit(id: WorkNodeId) {
            if (id in visited) return
            require(visiting.add(id)) { "Work graph contains a dependency cycle" }
            byId.getValue(id).dependencies.forEach(::visit)
            visiting.remove(id)
            visited.add(id)
        }

        nodes.forEach { visit(it.id) }
    }
}
