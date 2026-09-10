package app.lifeos.next.kernel

import app.lifeos.core.language.GoalFrame
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.capability.GoalCapabilityResolution
import app.lifeos.core.runtime.goal.GoalResumeBlockReason

sealed interface GoalResumeExecutionResult {
    data class Resumed(
        val targetGoalId: PhotonId,
        val sourcePhoton: Photon,
        val frame: GoalFrame,
        val resumedGoal: PhotonSubmissionResult,
        val routing: GoalCapabilityResolution,
    ) : GoalResumeExecutionResult

    data class Blocked(
        val reason: GoalResumeBlockReason,
        val message: String,
    ) : GoalResumeExecutionResult {
        init { require(message.isNotBlank()) }
    }

    data class Failed(val message: String) : GoalResumeExecutionResult {
        init { require(message.isNotBlank()) }
    }
}
