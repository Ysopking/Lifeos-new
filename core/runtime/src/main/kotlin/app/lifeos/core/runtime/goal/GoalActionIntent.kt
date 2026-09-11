package app.lifeos.core.runtime.goal

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.convergence.ConvergenceDecision
import app.lifeos.core.runtime.convergence.ConvergenceDecisionState

@JvmInline
value class GoalActionId(val value: String) {
    init {
        require(value.startsWith(PREFIX)) { "Invalid goal action id prefix" }
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}"))) {
            "Invalid goal action id digest"
        }
    }

    override fun toString(): String = value

    companion object {
        const val PREFIX = "goal-action:"
    }
}

data class GoalActionIntent(
    val actionId: GoalActionId,
    val idempotencyKey: String,
    val planId: GoalPlanId,
    val planRevision: Long,
    val stepId: GoalStepId,
    val sourceGoalPhotonId: PhotonId,
    val sourceGoalPhotonRevision: Long,
    val decisionId: String,
    val decisionSourceFingerprint: String,
) {
    init {
        require(idempotencyKey.isNotBlank())
        require(planRevision > 0L)
        require(sourceGoalPhotonRevision > 0L)
        require(decisionId.isNotBlank())
        require(decisionSourceFingerprint.isNotBlank())
    }
}

/** Creates an action identity that is invariant across process death and retries. */
class GoalActionIntentFactory {
    fun create(
        state: GoalPlanRuntimeState,
        stepId: GoalStepId,
        decision: ConvergenceDecision,
    ): GoalActionIntent {
        require(state.stepStates[stepId] == GoalStepState.READY) {
            "Goal action can only be prepared for a READY step"
        }
        require(decision.state == ConvergenceDecisionState.ACTIONABLE) {
            "Goal action requires an ACTIONABLE convergence decision"
        }
        val definition = state.definition
        require(definition.steps.any { it.id == stepId }) { "Unknown goal action step" }
        val digest = StableFieldIds.fingerprint(
            "goal-action/v1",
            definition.id.value,
            definition.planRevision.toString(),
            stepId.value,
            decision.id.value,
            decision.sourceFingerprint,
        )
        return GoalActionIntent(
            actionId = GoalActionId("${GoalActionId.PREFIX}$digest"),
            idempotencyKey = "${GoalActionId.PREFIX}$digest",
            planId = definition.id,
            planRevision = definition.planRevision,
            stepId = stepId,
            sourceGoalPhotonId = definition.sourceGoalPhotonId,
            sourceGoalPhotonRevision = definition.sourceGoalPhotonRevision,
            decisionId = decision.id.value,
            decisionSourceFingerprint = decision.sourceFingerprint,
        )
    }
}
