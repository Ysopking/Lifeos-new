package app.lifeos.next.ui.actions

import app.lifeos.core.runtime.goal.GoalPlanId
import app.lifeos.core.runtime.goal.GoalStepId
import app.lifeos.core.runtime.goal.GoalStepState
import app.lifeos.next.ui.goals.GoalPlanUiStatus
import app.lifeos.next.ui.goals.GoalStepPresentationKind
import app.lifeos.next.ui.goals.GoalWorkspaceUiModel

enum class ActionCenterKind {
    READY,
    NEEDS_OWNER,
}

data class ActionCenterItem(
    val planId: GoalPlanId,
    val stepId: GoalStepId,
    val projectTitle: String,
    val objective: String,
    val kind: ActionCenterKind,
    val prompt: String,
)

data class ActionCenterUiModel(
    val ready: List<ActionCenterItem>,
    val needsOwner: List<ActionCenterItem>,
)

object ActionCenterProjector {
    fun project(workspace: GoalWorkspaceUiModel): ActionCenterUiModel {
        val all = workspace.plans
            .asSequence()
            .filterNot {
                it.status == GoalPlanUiStatus.COMPLETED ||
                    it.status == GoalPlanUiStatus.CANCELLED
            }
            .flatMap { plan ->
                plan.steps.asSequence()
                    .filter { it.presentationKind == GoalStepPresentationKind.OWNER_ACTION }
                    .mapNotNull { step ->
                        val kind = when {
                            step.state == GoalStepState.READY ||
                                step.id == plan.nextStepId -> ActionCenterKind.READY

                            step.state in NEEDS_OWNER_STATES -> ActionCenterKind.NEEDS_OWNER
                            else -> null
                        } ?: return@mapNotNull null

                        val prompt = when (kind) {
                            ActionCenterKind.READY ->
                                "Setze das Projekt „${plan.title}“ fort und stoße den nächsten sicheren Schritt an: ${step.objective}"
                            ActionCenterKind.NEEDS_OWNER ->
                                "Kläre mit mir, was du für das Projekt „${plan.title}“ brauchst: ${step.objective}"
                        }
                        ActionCenterItem(
                            planId = plan.id,
                            stepId = step.id,
                            projectTitle = plan.title,
                            objective = step.objective,
                            kind = kind,
                            prompt = prompt,
                        )
                    }
            }
            .distinctBy { it.stepId }
            .sortedWith(
                compareBy<ActionCenterItem> { it.kind.ordinal }
                    .thenBy { it.projectTitle }
                    .thenBy { it.objective }
            )
            .toList()

        return ActionCenterUiModel(
            ready = all.filter { it.kind == ActionCenterKind.READY },
            needsOwner = all.filter { it.kind == ActionCenterKind.NEEDS_OWNER },
        )
    }

    private val NEEDS_OWNER_STATES = setOf(
        GoalStepState.BLOCKED,
        GoalStepState.WAITING_EVIDENCE,
        GoalStepState.WAITING_CAPABILITY,
        GoalStepState.FAILED,
        GoalStepState.REPLAN_REQUIRED,
    )
}
