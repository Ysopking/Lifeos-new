package app.lifeos.core.runtime.level7

import app.lifeos.core.field.StableFieldIds

enum class RehydrationStepKind {
    EXTENSION_REGISTRY_HEAD,
    ACTIVE_EXTENSION_SNAPSHOT,
    DYNAMIC_MODULE_HEAD,
    REPRESENTATION_HEAD,
    WORLD_EQUATION_HEAD,
    PRODUCTIVE_WORLD_HEAD,
    PRODUCTIVE_WORLD_SNAPSHOT,
    ACTIVE_BOOTENGINE_CYCLE,
    MEMORY_HEAD,
    WORLD_MODEL_HEAD,
    STRATEGY_HEAD,
    CALIBRATION_HEAD,
    GOAL_HIERARCHY_HEAD,
    LEARNING_WATERMARK,
    GOAL_HEAD,
    LEARNING_HEAD,
}

data class RehydrationStep(
    val kind: RehydrationStepKind,
    val exactRef: String,
) {
    init {
        require(exactRef.isNotBlank())
    }
}

data class BoundedRehydrationPlan private constructor(
    val id: String,
    val steps: List<RehydrationStep>,
) {
    init {
        require(steps.isNotEmpty())
        require(steps.size <= MAX_STEPS)
        require(steps.map { it.kind }.distinct().size == steps.size)
        require(id == expectedId())
    }

    val fullVaultScanAllowed: Boolean get() = false

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-bounded-rehydration-plan/v1",
        *steps.flatMap { listOf(it.kind.name, it.exactRef) }.toTypedArray(),
    )

    private fun expectedId(): String = "rehydration-plan:${fingerprint()}"

    companion object {
        const val MAX_STEPS: Int = 16

        fun create(steps: List<RehydrationStep>): BoundedRehydrationPlan {
            require(steps.isNotEmpty() && steps.size <= MAX_STEPS)
            val canonicalOrder = RehydrationStepKind.entries.withIndex().associate { it.value to it.index }
            val canonical = steps.sortedBy { canonicalOrder.getValue(it.kind) }
            require(canonical.map { it.kind }.distinct().size == canonical.size)
            val fp = StableFieldIds.fingerprint(
                "level7-bounded-rehydration-plan/v1",
                *canonical.flatMap { listOf(it.kind.name, it.exactRef) }.toTypedArray(),
            )
            return BoundedRehydrationPlan(
                id = "rehydration-plan:$fp",
                steps = canonical,
            )
        }
    }
}

data class ProcessDeathSemanticCheckpoint(
    val worldHeadFingerprint: String,
    val equationVersion: String,
    val cycleFingerprint: String,
    val decisionSemanticFingerprint: String,
    val learningLedgerHeadFingerprint: String,
) {
    init {
        require(worldHeadFingerprint.isNotBlank())
        require(equationVersion.isNotBlank())
        require(cycleFingerprint.isNotBlank())
        require(decisionSemanticFingerprint.isNotBlank())
        require(learningLedgerHeadFingerprint.isNotBlank())
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-process-death-semantic-checkpoint/v1",
        worldHeadFingerprint,
        equationVersion,
        cycleFingerprint,
        decisionSemanticFingerprint,
        learningLedgerHeadFingerprint,
    )
}
