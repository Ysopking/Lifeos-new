package app.lifeos.next.ui.goals

import app.lifeos.core.runtime.goal.GoalNextActionDeriver
import app.lifeos.core.runtime.goal.GoalPlanId
import app.lifeos.core.runtime.goal.GoalPlanRuntimeState
import app.lifeos.core.runtime.goal.GoalStepDefinition
import app.lifeos.core.runtime.goal.GoalStepState
import java.time.Instant

/** Pure read-only projection from the durable V7 goal-plan ledger into owner-facing UI state. */
object GoalWorkspaceProjector {
    private val nextActionDeriver = GoalNextActionDeriver()

    fun project(
        states: Map<GoalPlanId, GoalPlanRuntimeState>,
        at: Instant,
    ): GoalWorkspaceUiModel {
        val plans = states.values
            .map { state -> projectPlan(state, at) }
            .sortedWith(
                compareBy<GoalPlanUiModel> { it.status.sortRank() }
                    .thenByDescending { it.lastActivityAt }
                    .thenBy { it.id.value }
            )
        return GoalWorkspaceUiModel(plans)
    }

    private fun projectPlan(
        state: GoalPlanRuntimeState,
        at: Instant,
    ): GoalPlanUiModel {
        val derivation = nextActionDeriver.derive(state, at)
        val expiredIds = derivation.expiredStepIds.toSet()
        val orderedDefinitions = state.definition.steps
            .sortedWith(
                compareByDescending<GoalStepDefinition> { it.priority }
                    .thenBy { it.id.value }
            )
        val steps = orderedDefinitions.map { step ->
            GoalStepUiModel(
                id = step.id,
                key = step.key,
                objective = step.objective,
                presentationKind = step.presentationKind(),
                state = state.stepStates.getValue(step.id),
                deadline = step.deadline,
                priority = step.priority,
                dependencyIds = step.dependencyIds.sortedBy { it.value },
                unmetDependencyIds = derivation.blockedByDependencies[step.id].orEmpty(),
                expired = step.id in expiredIds,
                activeActionId = state.activeActionIds[step.id],
                outcomePhotonId = state.outcomePhotonIds[step.id],
            )
        }
        val title = steps.firstOrNull { it.presentationKind == GoalStepPresentationKind.OWNER_ACTION }
            ?.objective
            ?: steps.first().objective
        val currentStepId = steps
            .firstOrNull { it.state == GoalStepState.RUNNING }
            ?.id

        return GoalPlanUiModel(
            id = state.definition.id,
            title = title,
            status = planStatus(state),
            createdAt = state.definition.createdAt,
            lastActivityAt = state.history.lastOrNull()?.createdAt ?: state.definition.createdAt,
            revision = state.revision,
            completedSteps = state.stepStates.values.count { it == GoalStepState.COMPLETED },
            totalSteps = state.definition.steps.size,
            currentStepId = currentStepId,
            nextStepId = derivation.nextStepId,
            sourceGoalPhotonId = state.definition.sourceGoalPhotonId,
            sourceGoalPhotonRevision = state.definition.sourceGoalPhotonRevision,
            steps = steps,
        )
    }

    private fun planStatus(state: GoalPlanRuntimeState): GoalPlanUiStatus {
        val states = state.stepStates.values
        return when {
            states.any { it == GoalStepState.RUNNING } -> GoalPlanUiStatus.ACTIVE
            states.any { it == GoalStepState.READY } -> GoalPlanUiStatus.ACTIVE
            states.any { it == GoalStepState.REPLAN_REQUIRED } -> GoalPlanUiStatus.REPLAN_REQUIRED
            states.any { it == GoalStepState.FAILED } -> GoalPlanUiStatus.FAILED
            states.any { it == GoalStepState.BLOCKED } -> GoalPlanUiStatus.BLOCKED
            states.any {
                it == GoalStepState.WAITING_EVIDENCE ||
                    it == GoalStepState.WAITING_CAPABILITY ||
                    it == GoalStepState.PAUSED
            } -> GoalPlanUiStatus.WAITING
            states.all { it == GoalStepState.COMPLETED } -> GoalPlanUiStatus.COMPLETED
            states.all { it == GoalStepState.CANCELLED } -> GoalPlanUiStatus.CANCELLED
            else -> GoalPlanUiStatus.PLANNED
        }
    }

    private fun GoalStepDefinition.presentationKind(): GoalStepPresentationKind = when {
        key.startsWith(ACTION_PREFIX) -> GoalStepPresentationKind.OWNER_ACTION
        key == VERIFY_OUTCOME_KEY -> GoalStepPresentationKind.INTERNAL_VERIFICATION
        else -> GoalStepPresentationKind.OTHER
    }

    private fun GoalPlanUiStatus.sortRank(): Int = when (this) {
        GoalPlanUiStatus.ACTIVE -> 0
        GoalPlanUiStatus.REPLAN_REQUIRED -> 1
        GoalPlanUiStatus.FAILED -> 2
        GoalPlanUiStatus.BLOCKED -> 3
        GoalPlanUiStatus.WAITING -> 4
        GoalPlanUiStatus.PLANNED -> 5
        GoalPlanUiStatus.COMPLETED -> 6
        GoalPlanUiStatus.CANCELLED -> 7
    }

    private const val ACTION_PREFIX = "action:"
    private const val VERIFY_OUTCOME_KEY = "verify:outcome"
}
