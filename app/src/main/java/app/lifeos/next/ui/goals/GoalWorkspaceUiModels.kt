package app.lifeos.next.ui.goals

import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.goal.GoalPlanId
import app.lifeos.core.runtime.goal.GoalStepId
import app.lifeos.core.runtime.goal.GoalStepState
import java.time.Instant

enum class GoalPlanUiStatus {
    ACTIVE,
    REPLAN_REQUIRED,
    FAILED,
    BLOCKED,
    WAITING,
    PLANNED,
    COMPLETED,
    CANCELLED,
}

enum class GoalStepPresentationKind {
    OWNER_ACTION,
    INTERNAL_VERIFICATION,
    OTHER,
}

data class GoalStepUiModel(
    val id: GoalStepId,
    val key: String,
    val objective: String,
    val presentationKind: GoalStepPresentationKind,
    val state: GoalStepState,
    val deadline: Instant?,
    val priority: Int,
    val dependencyIds: List<GoalStepId>,
    val unmetDependencyIds: List<GoalStepId>,
    val expired: Boolean,
    val activeActionId: String?,
    val outcomePhotonId: PhotonId?,
)

data class GoalPlanUiModel(
    val id: GoalPlanId,
    val title: String,
    val status: GoalPlanUiStatus,
    val createdAt: Instant,
    val lastActivityAt: Instant,
    val revision: Long,
    val completedSteps: Int,
    val totalSteps: Int,
    val currentStepId: GoalStepId?,
    val nextStepId: GoalStepId?,
    val sourceGoalPhotonId: PhotonId,
    val sourceGoalPhotonRevision: Long,
    val steps: List<GoalStepUiModel>,
)

data class GoalWorkspaceUiModel(
    val plans: List<GoalPlanUiModel>,
) {
    companion object {
        fun empty(): GoalWorkspaceUiModel = GoalWorkspaceUiModel(emptyList())
    }
}
