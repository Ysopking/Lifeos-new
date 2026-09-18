package app.lifeos.core.runtime.level7

enum class GoalHierarchyNodeKind {
    OBJECTIVE,
    LONG_TERM_GOAL,
    GOAL,
    SUBGOAL,
    PLAN,
    STEP,
}

data class GoalHierarchyNode(
    val id: String,
    val kind: GoalHierarchyNodeKind,
    val parentId: String?,
    val priority: Double,
    val ownerAuthorityFingerprint: String,
    val resourceFingerprint: String,
    val worldCompatibilityFingerprint: String,
    val dependencyIds: Set<String>,
) {
    init {
        require(id.isNotBlank())
        require(parentId == null || parentId.isNotBlank())
        require(priority.isFinite() && priority in 0.0..1.0)
        require(ownerAuthorityFingerprint.isNotBlank())
        require(resourceFingerprint.isNotBlank())
        require(worldCompatibilityFingerprint.isNotBlank())
        require(id !in dependencyIds)
    }
}

data class GoalHierarchySnapshot(
    val revision: Long,
    val nodes: List<GoalHierarchyNode>,
    val fingerprint: String,
) {
    init {
        require(revision > 0L)
        require(nodes.isNotEmpty())
        require(nodes.map { it.id }.distinct().size == nodes.size)
        require(fingerprint.isNotBlank())
    }
}

interface GoalHierarchyRepository {
    suspend fun load(): GoalHierarchySnapshot?
    suspend fun compareAndSet(expectedRevision: Long?, next: GoalHierarchySnapshot): Boolean
}

data class GoalConflictResolution(
    val selectedIds: List<String>,
    val blockedIds: List<String>,
    val reason: String,
) {
    init {
        require(selectedIds.toSet().intersect(blockedIds.toSet()).isEmpty())
        require(reason.isNotBlank())
    }
}

class GoalHierarchyConflictResolver {
    fun resolve(nodes: Collection<GoalHierarchyNode>): GoalConflictResolution {
        val ordered = nodes.sortedWith(
            compareByDescending<GoalHierarchyNode> { it.priority }
                .thenBy { it.id }
        )
        val selected = mutableListOf<GoalHierarchyNode>()
        val blocked = mutableListOf<GoalHierarchyNode>()
        ordered.forEach { candidate ->
            val conflict = selected.any { active ->
                active.resourceFingerprint == candidate.resourceFingerprint &&
                    active.worldCompatibilityFingerprint != candidate.worldCompatibilityFingerprint
            }
            if (conflict) blocked += candidate else selected += candidate
        }
        return GoalConflictResolution(
            selectedIds = selected.map { it.id },
            blockedIds = blocked.map { it.id },
            reason = "priority-owner-resource-world-dependency-resolution",
        )
    }
}
