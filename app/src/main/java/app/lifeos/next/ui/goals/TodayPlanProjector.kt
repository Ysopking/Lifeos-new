package app.lifeos.next.ui.goals

import app.lifeos.core.runtime.goal.GoalPlanId
import app.lifeos.core.runtime.goal.GoalStepId
import app.lifeos.core.runtime.goal.GoalStepState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class TodayPlanTiming {
    OVERDUE,
    NOW,
    TODAY,
    UNSCHEDULED,
}

data class TodayPlanItem(
    val planId: GoalPlanId,
    val planTitle: String,
    val stepId: GoalStepId,
    val objective: String,
    val state: GoalStepState,
    val presentationKind: GoalStepPresentationKind,
    val timing: TodayPlanTiming,
    val deadline: Instant?,
    val priority: Int,
    val blockedByDependencies: Boolean,
)

data class TodayPlanUiModel(
    val date: LocalDate,
    val items: List<TodayPlanItem>,
) {
    val overdueCount: Int
        get() = items.count { it.timing == TodayPlanTiming.OVERDUE }

    val actionableCount: Int
        get() = items.count {
            !it.blockedByDependencies &&
                (it.state == GoalStepState.READY || it.state == GoalStepState.RUNNING)
        }
}

/**
 * Pure, read-only "Heute" projection over the existing V7 goal workspace.
 *
 * This is deliberately not a second planner: it never persists state, invents deadlines, or changes
 * V7 transitions. It only selects owner-relevant work that is already due, active or ready now.
 */
object TodayPlanProjector {
    fun project(
        workspace: GoalWorkspaceUiModel,
        at: Instant,
        zoneId: ZoneId,
    ): TodayPlanUiModel {
        val today = at.atZone(zoneId).toLocalDate()
        val items = workspace.plans
            .asSequence()
            .filterNot { it.status == GoalPlanUiStatus.COMPLETED || it.status == GoalPlanUiStatus.CANCELLED }
            .flatMap { plan ->
                plan.steps.asSequence().mapNotNull { step ->
                    projectStep(plan, step, at, today, zoneId)
                }
            }
            .sortedWith(
                compareBy<TodayPlanItem> { it.timing.sortRank() }
                    .thenByDescending { it.priority }
                    .thenBy { it.deadline ?: Instant.MAX }
                    .thenBy { it.planId.value }
                    .thenBy { it.stepId.value }
            )
            .toList()
        return TodayPlanUiModel(date = today, items = items)
    }

    private fun projectStep(
        plan: GoalPlanUiModel,
        step: GoalStepUiModel,
        at: Instant,
        today: LocalDate,
        zoneId: ZoneId,
    ): TodayPlanItem? {
        if (step.state == GoalStepState.COMPLETED || step.state == GoalStepState.CANCELLED) return null

        val deadlineDate = step.deadline?.atZone(zoneId)?.toLocalDate()
        val overdue = step.deadline?.isBefore(at) == true
        val dueToday = deadlineDate == today
        val current = step.id == plan.currentStepId || step.state == GoalStepState.RUNNING
        val readyOwnerAction = step.presentationKind == GoalStepPresentationKind.OWNER_ACTION &&
            (step.state == GoalStepState.READY || step.id == plan.nextStepId)

        if (!overdue && !dueToday && !current && !readyOwnerAction) return null

        val timing = when {
            overdue -> TodayPlanTiming.OVERDUE
            current -> TodayPlanTiming.NOW
            dueToday -> TodayPlanTiming.TODAY
            else -> TodayPlanTiming.UNSCHEDULED
        }
        return TodayPlanItem(
            planId = plan.id,
            planTitle = plan.title,
            stepId = step.id,
            objective = step.objective,
            state = step.state,
            presentationKind = step.presentationKind,
            timing = timing,
            deadline = step.deadline,
            priority = step.priority,
            blockedByDependencies = step.unmetDependencyIds.isNotEmpty(),
        )
    }

    private fun TodayPlanTiming.sortRank(): Int = when (this) {
        TodayPlanTiming.OVERDUE -> 0
        TodayPlanTiming.NOW -> 1
        TodayPlanTiming.TODAY -> 2
        TodayPlanTiming.UNSCHEDULED -> 3
    }
}
