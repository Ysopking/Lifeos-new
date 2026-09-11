package app.lifeos.core.runtime.goal

import java.time.Instant

data class GoalNextActionDerivation(
    val readyStepIds: List<GoalStepId>,
    val blockedByDependencies: Map<GoalStepId, List<GoalStepId>>,
    val expiredStepIds: List<GoalStepId>,
) {
    val nextStepId: GoalStepId?
        get() = readyStepIds.firstOrNull()
}

/**
 * Read-only V7 scheduler view. It never mutates plan state and never silently skips a deadline.
 * Priority is semantic; stable ids are only the deterministic tie-breaker among equally eligible steps.
 */
class GoalNextActionDeriver {
    fun derive(
        state: GoalPlanRuntimeState,
        at: Instant,
    ): GoalNextActionDerivation {
        val ready = mutableListOf<GoalStepDefinition>()
        val blocked = linkedMapOf<GoalStepId, List<GoalStepId>>()
        val expired = mutableListOf<GoalStepDefinition>()

        state.definition.steps
            .sortedWith(compareByDescending<GoalStepDefinition> { it.priority }.thenBy { it.id.value })
            .forEach { step ->
                val stepState = state.stepStates.getValue(step.id)
                if (stepState != GoalStepState.PLANNED && stepState != GoalStepState.READY) {
                    return@forEach
                }
                val unmet = step.dependencyIds
                    .filter { dependency -> state.stepStates.getValue(dependency) != GoalStepState.COMPLETED }
                    .sortedBy { it.value }
                if (unmet.isNotEmpty()) {
                    blocked[step.id] = unmet
                    return@forEach
                }
                if (step.deadline != null && at.isAfter(step.deadline)) {
                    expired += step
                    return@forEach
                }
                ready += step
            }

        return GoalNextActionDerivation(
            readyStepIds = ready.map { it.id },
            blockedByDependencies = blocked,
            expiredStepIds = expired
                .sortedWith(compareByDescending<GoalStepDefinition> { it.priority }.thenBy { it.id.value })
                .map { it.id },
        )
    }
}
