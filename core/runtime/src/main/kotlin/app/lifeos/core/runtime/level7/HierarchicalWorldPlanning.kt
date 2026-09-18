package app.lifeos.core.runtime.level7

import app.lifeos.core.field.StableFieldIds

data class WorldStateTarget(
    val semanticKey: String,
    val requiredStateFingerprint: String,
    val acceptanceFingerprint: String,
) {
    init {
        require(semanticKey.isNotBlank())
        require(requiredStateFingerprint.isNotBlank())
        require(acceptanceFingerprint.isNotBlank())
    }
}

data class WorldPlanStep(
    val id: String,
    val parentId: String?,
    val target: WorldStateTarget,
    val predictedTransitionFingerprint: String,
    val requiredEvidenceFingerprint: String,
) {
    init {
        require(id.isNotBlank())
        require(parentId == null || parentId.isNotBlank())
        require(predictedTransitionFingerprint.isNotBlank())
        require(requiredEvidenceFingerprint.isNotBlank())
    }
}

data class HierarchicalWorldPlan private constructor(
    val id: String,
    val sourceWorldSnapshotId: String,
    val goalId: String,
    val steps: List<WorldPlanStep>,
) {
    init {
        require(sourceWorldSnapshotId.isNotBlank())
        require(goalId.isNotBlank())
        require(steps.isNotEmpty())
        require(steps.map { it.id }.distinct().size == steps.size)
        require(id == expectedId())
    }

    val executionAuthority: Boolean get() = false
    val ownerAuthority: Boolean get() = false

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-hierarchical-world-plan/v1",
        sourceWorldSnapshotId,
        goalId,
        *steps.sortedBy { it.id }.flatMap {
            listOf(
                it.id,
                it.parentId.orEmpty(),
                it.target.semanticKey,
                it.target.requiredStateFingerprint,
                it.target.acceptanceFingerprint,
                it.predictedTransitionFingerprint,
                it.requiredEvidenceFingerprint,
            )
        }.toTypedArray(),
    )

    private fun expectedId(): String = "world-plan:${fingerprint()}"

    companion object {
        fun create(
            sourceWorldSnapshotId: String,
            goalId: String,
            steps: List<WorldPlanStep>,
        ): HierarchicalWorldPlan {
            val canonical = steps.sortedBy { it.id }
            val fp = StableFieldIds.fingerprint(
                "level7-hierarchical-world-plan/v1",
                sourceWorldSnapshotId,
                goalId,
                *canonical.flatMap {
                    listOf(
                        it.id,
                        it.parentId.orEmpty(),
                        it.target.semanticKey,
                        it.target.requiredStateFingerprint,
                        it.target.acceptanceFingerprint,
                        it.predictedTransitionFingerprint,
                        it.requiredEvidenceFingerprint,
                    )
                }.toTypedArray(),
            )
            return HierarchicalWorldPlan(
                id = "world-plan:$fp",
                sourceWorldSnapshotId = sourceWorldSnapshotId,
                goalId = goalId,
                steps = canonical,
            )
        }
    }
}
