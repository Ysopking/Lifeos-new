package app.lifeos.core.runtime.goal

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.convergence.ConvergenceDecision
import app.lifeos.core.runtime.convergence.ConvergenceDecisionState
import java.time.Instant

sealed interface GoalPlanExecutionPreparation {
    data class PreparedAction(
        val action: GoalActionIntent,
        val contract: GoalStepExecutionContract,
        val recovered: Boolean,
    ) : GoalPlanExecutionPreparation

    data class VerificationCompleted(
        val stepId: GoalStepId,
        val outcomePhotonId: PhotonId,
    ) : GoalPlanExecutionPreparation

    data class Waiting(
        val stepId: GoalStepId?,
        val state: GoalStepState?,
        val reason: String,
    ) : GoalPlanExecutionPreparation {
        init { require(reason.isNotBlank()) }
    }

    data class ReplanRequired(
        val stepId: GoalStepId,
        val reason: String,
    ) : GoalPlanExecutionPreparation

    data object PlanCompleted : GoalPlanExecutionPreparation
}

/**
 * V7-C/D coordinator around the append-only GoalPlan ledger. It persists RUNNING before returning an
 * effectful action to the caller and binds terminal state only after the caller has persisted an
 * outcome Photon. A reconstructed coordinator therefore recovers the same action identity instead
 * of creating a second host effect.
 */
class GoalPlanExecutionCoordinator(
    private val ledger: DurableGoalPlanLedger,
    private val nextAction: GoalNextActionDeriver = GoalNextActionDeriver(),
    private val decisionProjector: GoalStepDecisionProjector = GoalStepDecisionProjector(),
    private val actionFactory: GoalActionIntentFactory = GoalActionIntentFactory(),
) {
    suspend fun prepareNext(
        blueprint: GoalPlanBlueprint,
        decisions: Map<GoalStepId, ConvergenceDecision>,
        at: Instant,
    ): GoalPlanExecutionPreparation {
        var state = requireState(blueprint)

        val running = state.definition.steps
            .sortedWith(compareByDescending<GoalStepDefinition> { it.priority }.thenBy { it.id.value })
            .firstOrNull { state.stepStates.getValue(it.id) == GoalStepState.RUNNING }
        if (running != null) {
            val contract = blueprint.contract(running.id)
            return when (contract.kind) {
                GoalStepExecutionKind.ACTION -> GoalPlanExecutionPreparation.PreparedAction(
                    action = recoverAction(state, running.id),
                    contract = contract,
                    recovered = true,
                )
                GoalStepExecutionKind.VERIFY_OUTCOME -> resumeVerification(blueprint, state, running, at)
            }
        }

        if (state.stepStates.values.all { it == GoalStepState.COMPLETED }) {
            return GoalPlanExecutionPreparation.PlanCompleted
        }

        val derivation = nextAction.derive(state, at)
        derivation.expiredStepIds.firstOrNull()?.let { expiredId ->
            val current = state.stepStates.getValue(expiredId)
            val transition = GoalPlanTransition.create(
                planId = state.definition.id,
                predecessorId = state.headTransitionId,
                stepId = expiredId,
                fromState = current,
                toState = GoalStepState.REPLAN_REQUIRED,
                reason = "deadline-expired",
                sourceFingerprint = StableFieldIds.fingerprint(
                    "goal-deadline-expired/v1",
                    state.definition.id.value,
                    expiredId.value,
                    at.toString(),
                ),
                createdAt = at,
            )
            ledger.append(transition)
            return GoalPlanExecutionPreparation.ReplanRequired(expiredId, "deadline-expired")
        }

        val stepId = derivation.nextStepId
            ?: return GoalPlanExecutionPreparation.Waiting(
                stepId = null,
                state = null,
                reason = if (derivation.blockedByDependencies.isNotEmpty()) {
                    "waiting-for-step-dependencies"
                } else {
                    "no-runnable-step"
                },
            )
        val step = state.definition.steps.single { it.id == stepId }
        val contract = blueprint.contract(stepId)
        return when (contract.kind) {
            GoalStepExecutionKind.VERIFY_OUTCOME -> executeVerification(blueprint, state, step, at)
            GoalStepExecutionKind.ACTION -> {
                val decision = decisions[stepId]
                    ?: return GoalPlanExecutionPreparation.Waiting(
                        stepId = stepId,
                        state = state.stepStates.getValue(stepId),
                        reason = "convergence-decision-required",
                    )
                if (decision.state != ConvergenceDecisionState.ACTIONABLE ||
                    state.stepStates.getValue(stepId) != GoalStepState.READY
                ) {
                    when (val projection = decisionProjector.project(state, stepId, decision, at)) {
                        is GoalStepDecisionProjectionResult.Transitioned -> {
                            state = ledger.append(projection.transition).state
                        }
                        is GoalStepDecisionProjectionResult.Unchanged -> Unit
                    }
                }
                if (state.stepStates.getValue(stepId) != GoalStepState.READY) {
                    return GoalPlanExecutionPreparation.Waiting(
                        stepId = stepId,
                        state = state.stepStates.getValue(stepId),
                        reason = "step-not-actionable:${decision.state.name}",
                    )
                }
                require(decision.state == ConvergenceDecisionState.ACTIONABLE) {
                    "READY action step requires ACTIONABLE convergence decision"
                }
                val action = actionFactory.create(state, stepId, decision)
                val runningTransition = GoalPlanTransition.create(
                    planId = state.definition.id,
                    predecessorId = state.headTransitionId,
                    stepId = stepId,
                    fromState = GoalStepState.READY,
                    toState = GoalStepState.RUNNING,
                    reason = "action-prepared:${action.actionId.value}",
                    sourceFingerprint = decision.sourceFingerprint,
                    decisionFingerprint = decision.id.value,
                    actionId = action.actionId.value,
                    actionIdempotencyKey = action.idempotencyKey,
                    createdAt = at,
                )
                ledger.append(runningTransition)
                GoalPlanExecutionPreparation.PreparedAction(
                    action = action,
                    contract = contract,
                    recovered = false,
                )
            }
        }
    }

    suspend fun recordOutcome(
        blueprint: GoalPlanBlueprint,
        stepId: GoalStepId,
        actionIdempotencyKey: String,
        outcomePhotonId: PhotonId,
        succeeded: Boolean,
        sourceFingerprint: String,
        at: Instant,
    ): GoalPlanRuntimeState {
        require(actionIdempotencyKey.isNotBlank())
        require(sourceFingerprint.isNotBlank())
        val state = requireState(blueprint)
        val current = state.stepStates[stepId] ?: error("Unknown goal outcome step")
        if (current == GoalStepState.COMPLETED || current == GoalStepState.FAILED) {
            val terminal = state.history.lastOrNull {
                it.stepId == stepId &&
                    it.toState == current &&
                    it.actionIdempotencyKey == actionIdempotencyKey &&
                    it.outcomePhotonId == outcomePhotonId
            }
            require(terminal != null) { "Terminal goal outcome replay does not match durable history" }
            return state
        }
        require(current == GoalStepState.RUNNING) { "Goal outcome requires RUNNING step" }
        val running = state.history.lastOrNull {
            it.stepId == stepId && it.toState == GoalStepState.RUNNING
        } ?: error("RUNNING goal step is missing preparation transition")
        require(running.actionIdempotencyKey == actionIdempotencyKey) {
            "Goal outcome idempotency key differs from prepared action"
        }
        val terminal = GoalPlanTransition.create(
            planId = state.definition.id,
            predecessorId = state.headTransitionId,
            stepId = stepId,
            fromState = GoalStepState.RUNNING,
            toState = if (succeeded) GoalStepState.COMPLETED else GoalStepState.FAILED,
            reason = if (succeeded) "action-outcome-persisted" else "action-outcome-failed",
            sourceFingerprint = sourceFingerprint,
            decisionFingerprint = running.decisionFingerprint,
            actionId = running.actionId,
            actionIdempotencyKey = running.actionIdempotencyKey,
            outcomePhotonId = outcomePhotonId,
            createdAt = at,
        )
        return ledger.append(terminal).state
    }

    private suspend fun executeVerification(
        blueprint: GoalPlanBlueprint,
        state: GoalPlanRuntimeState,
        step: GoalStepDefinition,
        at: Instant,
    ): GoalPlanExecutionPreparation {
        require(step.dependencyIds.size == 1) {
            "V7 outcome verification currently requires exactly one dependency"
        }
        val dependencyId = step.dependencyIds.single()
        val outcome = state.outcomePhotonIds[dependencyId]
            ?: return GoalPlanExecutionPreparation.Waiting(
                stepId = step.id,
                state = state.stepStates.getValue(step.id),
                reason = "verification-outcome-not-persisted",
            )
        val sourceFingerprint = StableFieldIds.fingerprint(
            "goal-outcome-verification/v1",
            blueprint.definition.id.value,
            step.id.value,
            dependencyId.value,
            outcome.value,
        )
        var next = state
        if (next.stepStates.getValue(step.id) == GoalStepState.PLANNED) {
            next = ledger.append(
                GoalPlanTransition.create(
                    planId = next.definition.id,
                    predecessorId = next.headTransitionId,
                    stepId = step.id,
                    fromState = GoalStepState.PLANNED,
                    toState = GoalStepState.READY,
                    reason = "persisted-outcome-ready-for-verification",
                    sourceFingerprint = sourceFingerprint,
                    createdAt = at,
                )
            ).state
        }
        val actionId = internalVerificationActionId(next, step.id, outcome)
        next = ledger.append(
            GoalPlanTransition.create(
                planId = next.definition.id,
                predecessorId = next.headTransitionId,
                stepId = step.id,
                fromState = GoalStepState.READY,
                toState = GoalStepState.RUNNING,
                reason = "internal-outcome-verification-started",
                sourceFingerprint = sourceFingerprint,
                actionId = actionId,
                actionIdempotencyKey = actionId,
                createdAt = at,
            )
        ).state
        return finishVerification(next, step.id, outcome, sourceFingerprint, actionId, at)
    }

    private suspend fun resumeVerification(
        blueprint: GoalPlanBlueprint,
        state: GoalPlanRuntimeState,
        step: GoalStepDefinition,
        at: Instant,
    ): GoalPlanExecutionPreparation {
        require(step.dependencyIds.size == 1)
        val dependencyId = step.dependencyIds.single()
        val outcome = state.outcomePhotonIds[dependencyId]
            ?: return GoalPlanExecutionPreparation.Waiting(
                stepId = step.id,
                state = GoalStepState.RUNNING,
                reason = "verification-outcome-missing-after-restart",
            )
        val running = state.history.last { it.stepId == step.id && it.toState == GoalStepState.RUNNING }
        return finishVerification(
            state = state,
            stepId = step.id,
            outcome = outcome,
            sourceFingerprint = running.sourceFingerprint,
            actionId = requireNotNull(running.actionId),
            at = at,
        )
    }

    private suspend fun finishVerification(
        state: GoalPlanRuntimeState,
        stepId: GoalStepId,
        outcome: PhotonId,
        sourceFingerprint: String,
        actionId: String,
        at: Instant,
    ): GoalPlanExecutionPreparation {
        ledger.append(
            GoalPlanTransition.create(
                planId = state.definition.id,
                predecessorId = state.headTransitionId,
                stepId = stepId,
                fromState = GoalStepState.RUNNING,
                toState = GoalStepState.COMPLETED,
                reason = "persisted-outcome-verified",
                sourceFingerprint = sourceFingerprint,
                actionId = actionId,
                actionIdempotencyKey = actionId,
                outcomePhotonId = outcome,
                createdAt = at,
            )
        )
        return GoalPlanExecutionPreparation.VerificationCompleted(stepId, outcome)
    }

    private fun recoverAction(
        state: GoalPlanRuntimeState,
        stepId: GoalStepId,
    ): GoalActionIntent {
        val running = state.history.lastOrNull {
            it.stepId == stepId && it.toState == GoalStepState.RUNNING
        } ?: error("RUNNING goal step is missing durable preparation")
        val actionId = GoalActionId(requireNotNull(running.actionId))
        val idempotencyKey = requireNotNull(running.actionIdempotencyKey)
        val decisionId = requireNotNull(running.decisionFingerprint) {
            "Effectful RUNNING action is missing convergence lineage"
        }
        return GoalActionIntent(
            actionId = actionId,
            idempotencyKey = idempotencyKey,
            planId = state.definition.id,
            planRevision = state.definition.planRevision,
            stepId = stepId,
            sourceGoalPhotonId = state.definition.sourceGoalPhotonId,
            sourceGoalPhotonRevision = state.definition.sourceGoalPhotonRevision,
            decisionId = decisionId,
            decisionSourceFingerprint = running.sourceFingerprint,
        )
    }

    private fun internalVerificationActionId(
        state: GoalPlanRuntimeState,
        stepId: GoalStepId,
        outcome: PhotonId,
    ): String = GoalActionId.PREFIX + StableFieldIds.fingerprint(
        "goal-internal-verification-action/v1",
        state.definition.id.value,
        state.definition.planRevision.toString(),
        stepId.value,
        outcome.value,
    )

    private fun requireState(blueprint: GoalPlanBlueprint): GoalPlanRuntimeState {
        val state = requireNotNull(ledger.state(blueprint.definition.id)) {
            "Goal plan must be created or rehydrated before execution"
        }
        require(state.definition == blueprint.definition) { "Goal blueprint differs from durable plan" }
        return state
    }
}
