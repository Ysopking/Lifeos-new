package app.lifeos.next.ui.actions

import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.goal.GoalPlanId
import app.lifeos.core.runtime.goal.GoalStepId
import app.lifeos.core.runtime.goal.GoalStepState
import app.lifeos.next.ui.goals.GoalPlanUiModel
import app.lifeos.next.ui.goals.GoalPlanUiStatus
import app.lifeos.next.ui.goals.GoalStepPresentationKind
import app.lifeos.next.ui.goals.GoalStepUiModel
import app.lifeos.next.ui.goals.GoalWorkspaceUiModel
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActionCenterProjectorTest {
    @Test
    fun separatesReadyOwnerActionsFromItemsThatNeedClarification() {
        val readyId = GoalStepId(GoalStepId.PREFIX + "b".repeat(64))
        val blockedId = GoalStepId(GoalStepId.PREFIX + "c".repeat(64))
        val plan = GoalPlanUiModel(
            id = GoalPlanId(GoalPlanId.PREFIX + "a".repeat(64)),
            title = "LIFEOS",
            status = GoalPlanUiStatus.ACTIVE,
            createdAt = Instant.EPOCH,
            lastActivityAt = Instant.EPOCH,
            revision = 1,
            completedSteps = 0,
            totalSteps = 2,
            currentStepId = readyId,
            nextStepId = readyId,
            sourceGoalPhotonId = PhotonId("goal-source"),
            sourceGoalPhotonRevision = 1,
            steps = listOf(
                step(readyId, "Build starten", GoalStepState.READY),
                step(blockedId, "Freigabe klären", GoalStepState.BLOCKED),
            ),
        )

        val result = ActionCenterProjector.project(
            GoalWorkspaceUiModel(listOf(plan))
        )

        assertEquals(listOf("Build starten"), result.ready.map { it.objective })
        assertEquals(listOf("Freigabe klären"), result.needsOwner.map { it.objective })
        assertTrue(result.ready.single().prompt.contains("anstoße"))
        assertTrue(result.needsOwner.single().prompt.contains("Kläre"))
    }

    private fun step(
        id: GoalStepId,
        objective: String,
        state: GoalStepState,
    ): GoalStepUiModel = GoalStepUiModel(
        id = id,
        key = objective.lowercase().replace(' ', '-'),
        objective = objective,
        presentationKind = GoalStepPresentationKind.OWNER_ACTION,
        state = state,
        deadline = null,
        priority = 0,
        dependencyIds = emptyList(),
        unmetDependencyIds = emptyList(),
        expired = false,
        activeActionId = null,
        outcomePhotonId = null,
    )
}
