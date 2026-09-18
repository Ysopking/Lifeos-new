package app.lifeos.core.runtime.level7

import app.lifeos.core.field.StableFieldIds

enum class GeneratedGoalState {
    PROPOSED,
    ACTIVE,
    COMPLETED,
    BLOCKED,
}

data class GoalTargetField(
    val worldTargetFingerprint: String,
    val desiredStateFingerprint: String,
    val priority: Double,
) {
    init {
        require(worldTargetFingerprint.isNotBlank())
        require(desiredStateFingerprint.isNotBlank())
        require(priority.isFinite() && priority in 0.0..1.0)
    }
}

data class GeneratedGoal private constructor(
    val id: String,
    val semanticKey: String,
    val parentGoalId: String?,
    val state: GeneratedGoalState,
    val targetField: GoalTargetField,
    val provenanceFingerprint: String,
    val authorityDecisionId: String?,
) {
    init {
        require(semanticKey.isNotBlank())
        require(parentGoalId == null || parentGoalId.isNotBlank())
        require(provenanceFingerprint.isNotBlank())
        if (state == GeneratedGoalState.ACTIVE) {
            require(!authorityDecisionId.isNullOrBlank()) {
                "System-generated goal cannot activate without authority decision"
            }
        }
        require(id == expectedId())
    }

    val executionAuthority: Boolean get() = false

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-generated-goal/v1",
        semanticKey,
        parentGoalId.orEmpty(),
        state.name,
        targetField.worldTargetFingerprint,
        targetField.desiredStateFingerprint,
        java.lang.Double.toHexString(targetField.priority),
        provenanceFingerprint,
        authorityDecisionId.orEmpty(),
    )

    private fun expectedId(): String = "generated-goal:${fingerprint()}"

    fun activate(authorityDecisionId: String): GeneratedGoal {
        require(state == GeneratedGoalState.PROPOSED)
        require(authorityDecisionId.isNotBlank())
        return createInternal(
            semanticKey,
            parentGoalId,
            GeneratedGoalState.ACTIVE,
            targetField,
            provenanceFingerprint,
            authorityDecisionId,
        )
    }

    companion object {
        fun propose(
            semanticKey: String,
            parentGoalId: String?,
            targetField: GoalTargetField,
            provenanceFingerprint: String,
        ): GeneratedGoal = createInternal(
            semanticKey,
            parentGoalId,
            GeneratedGoalState.PROPOSED,
            targetField,
            provenanceFingerprint,
            null,
        )

        private fun createInternal(
            semanticKey: String,
            parentGoalId: String?,
            state: GeneratedGoalState,
            targetField: GoalTargetField,
            provenanceFingerprint: String,
            authorityDecisionId: String?,
        ): GeneratedGoal {
            val fp = StableFieldIds.fingerprint(
                "level7-generated-goal/v1",
                semanticKey,
                parentGoalId.orEmpty(),
                state.name,
                targetField.worldTargetFingerprint,
                targetField.desiredStateFingerprint,
                java.lang.Double.toHexString(targetField.priority),
                provenanceFingerprint,
                authorityDecisionId.orEmpty(),
            )
            return GeneratedGoal(
                id = "generated-goal:$fp",
                semanticKey = semanticKey,
                parentGoalId = parentGoalId,
                state = state,
                targetField = targetField,
                provenanceFingerprint = provenanceFingerprint,
                authorityDecisionId = authorityDecisionId,
            )
        }
    }
}
