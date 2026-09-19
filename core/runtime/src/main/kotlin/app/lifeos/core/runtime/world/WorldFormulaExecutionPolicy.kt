package app.lifeos.core.runtime.world

enum class WorldFormulaExecutionScope {
    PRODUCTIVE_COGNITIVE,
    RESOURCE,
    SELF_OBSERVATION,
    SHADOW,
    COUNTERFACTUAL,
}

data class WorldFormulaExecutionPolicy(
    val scope: WorldFormulaExecutionScope,
    val captureCognitiveSnapshots: Boolean,
    val emitCognitiveTriggers: Boolean,
    val productiveCommitAllowed: Boolean,
) {
    init {
        if (scope != WorldFormulaExecutionScope.PRODUCTIVE_COGNITIVE) {
            require(!productiveCommitAllowed) {
                "Non-productive WorldFormula scopes cannot commit productive world state"
            }
        }
    }

    companion object {
        val PRODUCTIVE = WorldFormulaExecutionPolicy(
            scope = WorldFormulaExecutionScope.PRODUCTIVE_COGNITIVE,
            captureCognitiveSnapshots = true,
            emitCognitiveTriggers = true,
            productiveCommitAllowed = true,
        )

        val RESOURCE = WorldFormulaExecutionPolicy(
            scope = WorldFormulaExecutionScope.RESOURCE,
            captureCognitiveSnapshots = false,
            emitCognitiveTriggers = false,
            productiveCommitAllowed = false,
        )

        val SELF_OBSERVATION = WorldFormulaExecutionPolicy(
            scope = WorldFormulaExecutionScope.SELF_OBSERVATION,
            captureCognitiveSnapshots = false,
            emitCognitiveTriggers = false,
            productiveCommitAllowed = false,
        )

        val SHADOW = WorldFormulaExecutionPolicy(
            scope = WorldFormulaExecutionScope.SHADOW,
            captureCognitiveSnapshots = false,
            emitCognitiveTriggers = false,
            productiveCommitAllowed = false,
        )

        val COUNTERFACTUAL = WorldFormulaExecutionPolicy(
            scope = WorldFormulaExecutionScope.COUNTERFACTUAL,
            captureCognitiveSnapshots = false,
            emitCognitiveTriggers = false,
            productiveCommitAllowed = false,
        )
    }
}

interface WorldFormulaExecutor {
    val scope: WorldFormulaExecutionScope

    suspend fun evaluate(request: WorldFormulaRequest): WorldFormulaExecution
}
