package app.lifeos.next

import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.goal.GoalPlanDefinition
import app.lifeos.core.runtime.goal.GoalStepSpec
import app.lifeos.next.ui.goals.GoalPlanUiModel
import app.lifeos.next.ui.goals.GoalPlanUiStatus
import app.lifeos.next.ui.goals.GoalWorkspaceUiModel
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LifeOsGoalsUiStateTest {
    private val now = Instant.parse("2026-09-13T20:00:00Z")

    @Test
    fun defaultFilterShowsActionableAndProblemPlansOnly() {
        val active = plan("active", GoalPlanUiStatus.ACTIVE)
        val blocked = plan("blocked", GoalPlanUiStatus.BLOCKED)
        val planned = plan("planned", GoalPlanUiStatus.PLANNED)
        val waiting = plan("waiting", GoalPlanUiStatus.WAITING)
        val completed = plan("completed", GoalPlanUiStatus.COMPLETED)

        val state = LifeOsGoalsUiState(
            workspace = GoalWorkspaceUiModel(listOf(active, blocked, planned, waiting, completed)),
            loading = false,
        )

        assertEquals(
            listOf(active.id, blocked.id, planned.id),
            state.visiblePlans.map { it.id },
        )
    }

    @Test
    fun waitingAndDoneFiltersStayDisjoint() {
        val waiting = plan("waiting", GoalPlanUiStatus.WAITING)
        val completed = plan("completed", GoalPlanUiStatus.COMPLETED)
        val cancelled = plan("cancelled", GoalPlanUiStatus.CANCELLED)
        val workspace = GoalWorkspaceUiModel(listOf(waiting, completed, cancelled))

        assertEquals(
            listOf(waiting.id),
            LifeOsGoalsUiState(
                workspace = workspace,
                filter = GoalWorkspaceFilter.WAITING,
                loading = false,
            ).visiblePlans.map { it.id },
        )
        assertEquals(
            listOf(completed.id, cancelled.id),
            LifeOsGoalsUiState(
                workspace = workspace,
                filter = GoalWorkspaceFilter.DONE,
                loading = false,
            ).visiblePlans.map { it.id },
        )
    }

    @Test
    fun selectedPlanResolvesOnlyAgainstCurrentWorkspace() {
        val active = plan("active-selected", GoalPlanUiStatus.ACTIVE)
        val selected = LifeOsGoalsUiState(
            workspace = GoalWorkspaceUiModel(listOf(active)),
            selectedPlanId = active.id,
            loading = false,
        )
        val missing = selected.copy(workspace = GoalWorkspaceUiModel.empty())

        assertEquals(active, selected.selectedPlan)
        assertNull(missing.selectedPlan)
    }

    private fun plan(suffix: String, status: GoalPlanUiStatus): GoalPlanUiModel {
        val definition = GoalPlanDefinition.create(
            sourceGoalPhotonId = PhotonId("goal-$suffix"),
            sourceGoalPhotonRevision = 1L,
            stepSpecs = listOf(GoalStepSpec("action:test", "Goal $suffix")),
            createdAt = now,
        )
        return GoalPlanUiModel(
            id = definition.id,
            title = "Goal $suffix",
            status = status,
            createdAt = now,
            lastActivityAt = now,
            revision = 0L,
            completedSteps = 0,
            totalSteps = 1,
            currentStepId = null,
            nextStepId = null,
            sourceGoalPhotonId = definition.sourceGoalPhotonId,
            sourceGoalPhotonRevision = definition.sourceGoalPhotonRevision,
            steps = emptyList(),
        )
    }
}
