package app.lifeos.core.runtime.goal

import app.lifeos.core.runtime.convergence.ConvergenceDecision
import app.lifeos.core.runtime.convergence.ConvergenceDecisionState
import java.time.Instant

sealed interface GoalStepDecisionProjectionResult {
    data class Transitioned(val transition: GoalPlanTransition) : GoalStepDecisionProjectionResult
    data class Unchanged(val state: GoalStepState, val reason: String) : GoalStepDecisionProjectionResult
}

/** Projects authoritative V5 convergence state into V7 plan state without bypassing pause/terminal policy. */
class GoalStepDecisionProjector {
    fun project(
        state: GoalPlanRuntimeState,
        stepId: GoalStepId,
        decision: ConvergenceDecision,
        at: Instant,
    ): GoalStepDecisionProjectionResult {
        val current = state.stepStates[stepId] ?: error("Unknown goal step")
        if (current in setOf(
                GoalStepState.RUNNING,
                GoalStepState.PAUSED,
                GoalStepState.COMPLETED,
                GoalStepState.CANCELLED,
                GoalStepState.FAILED,
                GoalStepState.REPLAN_REQUIRED,
            )
        ) {
            return GoalStepDecisionProjectionResult.Unchanged(
                state = current,
                reason = "state-not-convergence-mutable:${current.name}",
            )
        }

        val target = when (decision.state) {
            ConvergenceDecisionState.ACTIONABLE -> GoalStepState.READY
            ConvergenceDecisionState.CAPABILITY_REQUIRED -> GoalStepState.WAITING_CAPABILITY
            ConvergenceDecisionState.EVIDENCE_REQUIRED,
            ConvergenceDecisionState.CONFLICTED,
            ConvergenceDecisionState.UNRESOLVED -> GoalStepState.WAITING_EVIDENCE
        }
        if (current == target) {
            return GoalStepDecisionProjectionResult.Unchanged(
                state = current,
                reason = "convergence-state-already-projected:${decision.id.value}",
            )
        }
        if (current == GoalStepState.BLOCKED) {
            return GoalStepDecisionProjectionResult.Unchanged(
                state = current,
                reason = "explicit-blocker-must-be-resolved-before-convergence",
            )
        }

        return GoalStepDecisionProjectionResult.Transitioned(
            GoalPlanTransition.create(
                planId = state.definition.id,
                predecessorId = state.headTransitionId,
                stepId = stepId,
                fromState = current,
                toState = target,
                reason = "convergence:${decision.state.name}:${decision.reasons.joinToString("|")}",
                sourceFingerprint = decision.sourceFingerprint,
                decisionFingerprint = decision.id.value,
                createdAt = at,
            )
        )
    }
}
