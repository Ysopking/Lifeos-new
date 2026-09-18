package app.lifeos.core.runtime.level7

import app.lifeos.core.field.StableFieldIds

enum class PlanStepKind {
    RETRIEVE,
    RESEARCH,
    SIMULATE,
    ACTION,
    VERIFY,
    WAIT,
    ASK,
    BRANCH,
    JOIN,
    FALLBACK,
}

data class HierarchicalPlanNode(
    val id: String,
    val kind: PlanStepKind,
    val parentId: String?,
    val dependencies: Set<String>,
    val alternativeGroup: String?,
    val conditionRef: String?,
    val resourceBudgetFingerprint: String,
    val verificationContractFingerprint: String,
    val targetStateFingerprint: String,
) {
    init {
        require(id.isNotBlank())
        require(parentId == null || parentId.isNotBlank())
        require(id !in dependencies)
        require(dependencies.none { it.isBlank() })
        require(alternativeGroup == null || alternativeGroup.isNotBlank())
        require(conditionRef == null || conditionRef.isNotBlank())
        require(resourceBudgetFingerprint.isNotBlank())
        require(verificationContractFingerprint.isNotBlank())
        require(targetStateFingerprint.isNotBlank())
    }
}

data class HierarchicalPlanCandidate(
    val id: String,
    val sourceWorldSnapshotId: String,
    val goalId: String,
    val nodes: List<HierarchicalPlanNode>,
    val unresolved: Boolean,
    val score: Double,
) {
    init {
        require(id.isNotBlank())
        require(sourceWorldSnapshotId.isNotBlank())
        require(goalId.isNotBlank())
        require(nodes.isNotEmpty())
        require(nodes.map { it.id }.distinct().size == nodes.size)
        require(score.isFinite())
    }
}

class HierarchicalTaskDecomposer {
    fun decompose(
        sourceWorldSnapshotId: String,
        goalId: String,
        requiredResearch: Boolean,
        capabilityGap: Boolean,
    ): List<HierarchicalPlanNode> {
        val nodes = mutableListOf<HierarchicalPlanNode>()
        fun add(kind: PlanStepKind, parent: String?, target: String): String {
            val id = "plan-node:${StableFieldIds.fingerprint(
                sourceWorldSnapshotId,
                goalId,
                kind.name,
                parent.orEmpty(),
                target,
                nodes.size.toString(),
            )}"
            nodes += HierarchicalPlanNode(
                id = id,
                kind = kind,
                parentId = parent,
                dependencies = parent?.let(::setOf).orEmpty(),
                alternativeGroup = null,
                conditionRef = null,
                resourceBudgetFingerprint = "budget:$goalId",
                verificationContractFingerprint = "verify:$target",
                targetStateFingerprint = target,
            )
            return id
        }

        var parent: String? = null
        if (requiredResearch) parent = add(PlanStepKind.RESEARCH, parent, "evidence-ready")
        if (capabilityGap) parent = add(PlanStepKind.RETRIEVE, parent, "capability-ready")
        parent = add(PlanStepKind.SIMULATE, parent, "simulated")
        parent = add(PlanStepKind.ACTION, parent, "acted")
        add(PlanStepKind.VERIFY, parent, "verified")
        return nodes
    }
}

class PlanSearchEngine(
    private val maxFrontier: Int = 64,
) {
    init { require(maxFrontier in 1..256) }

    fun select(
        candidates: List<HierarchicalPlanCandidate>,
    ): HierarchicalPlanCandidate? =
        candidates
            .asSequence()
            .filterNot { it.unresolved }
            .sortedWith(
                compareByDescending<HierarchicalPlanCandidate> { it.score }
                    .thenBy { it.nodes.size }
                    .thenBy { it.id }
            )
            .take(maxFrontier)
            .firstOrNull()
}

data class PlanFailure(
    val failedNodeId: String,
    val reason: String,
) {
    init {
        require(failedNodeId.isNotBlank())
        require(reason.isNotBlank())
    }
}

class PlanRepairEngine {
    fun repair(
        plan: HierarchicalPlanCandidate,
        failure: PlanFailure,
        replacement: List<HierarchicalPlanNode>,
    ): HierarchicalPlanCandidate {
        require(plan.nodes.any { it.id == failure.failedNodeId })
        val affected = descendants(plan.nodes, failure.failedNodeId) + failure.failedNodeId
        val preserved = plan.nodes.filterNot { it.id in affected }
        val nodes = (preserved + replacement).distinctBy { it.id }
        val id = "repaired-plan:${StableFieldIds.fingerprint(
            plan.id,
            failure.failedNodeId,
            failure.reason,
            *nodes.sortedBy { it.id }.map { it.id }.toTypedArray(),
        )}"
        return plan.copy(id = id, nodes = nodes, unresolved = false)
    }

    private fun descendants(
        nodes: List<HierarchicalPlanNode>,
        root: String,
    ): Set<String> {
        val found = linkedSetOf<String>()
        var frontier = setOf(root)
        while (frontier.isNotEmpty()) {
            val next = nodes
                .filter { it.parentId in frontier || it.dependencies.any(frontier::contains) }
                .mapTo(linkedSetOf()) { it.id }
                .filterNotTo(linkedSetOf(), found::contains)
            if (next.isEmpty()) break
            found += next
            frontier = next
        }
        return found
    }
}
