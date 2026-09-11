package app.lifeos.core.runtime.goal

import app.lifeos.core.field.HypothesisId
import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.convergence.ConvergenceDecision
import app.lifeos.core.runtime.convergence.ConvergenceDecisionId
import app.lifeos.core.runtime.convergence.ConvergenceDecisionState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class GoalStepDecisionProjectorTest {
    private val at = Instant.parse("2026-09-11T13:00:00Z")

    @Test
    fun `actionable decision projects planned step to ready with exact lineage`() {
        val state = GoalPlanRuntimeState.initial(plan())
        val step = state.definition.steps.first()
        val decision = decision(ConvergenceDecisionState.ACTIONABLE, "actionable")

        val result = GoalStepDecisionProjector().project(state, step.id, decision, at)
        val transition = assertIs<GoalStepDecisionProjectionResult.Transitioned>(result).transition

        assertEquals(GoalStepState.PLANNED, transition.fromState)
        assertEquals(GoalStepState.READY, transition.toState)
        assertEquals(decision.id.value, transition.decisionFingerprint)
        assertEquals(decision.sourceFingerprint, transition.sourceFingerprint)
        assertEquals(state.headTransitionId, transition.predecessorId)
    }

    @Test
    fun `non actionable decisions remain explicit wait states`() {
        val state = GoalPlanRuntimeState.initial(plan())
        val step = state.definition.steps.first()
        val projector = GoalStepDecisionProjector()

        val evidence = assertIs<GoalStepDecisionProjectionResult.Transitioned>(
            projector.project(
                state,
                step.id,
                decision(ConvergenceDecisionState.EVIDENCE_REQUIRED, "evidence"),
                at,
            )
        ).transition
        assertEquals(GoalStepState.WAITING_EVIDENCE, evidence.toState)

        val capability = assertIs<GoalStepDecisionProjectionResult.Transitioned>(
            projector.project(
                state,
                step.id,
                decision(ConvergenceDecisionState.CAPABILITY_REQUIRED, "capability"),
                at,
            )
        ).transition
        assertEquals(GoalStepState.WAITING_CAPABILITY, capability.toState)
    }

    @Test
    fun `paused step cannot be reactivated by later convergence`() {
        val plan = plan()
        val step = plan.steps.first()
        val pausedTransition = GoalPlanTransition.create(
            planId = plan.id,
            predecessorId = null,
            stepId = step.id,
            fromState = GoalStepState.PLANNED,
            toState = GoalStepState.PAUSED,
            reason = "owner-paused",
            sourceFingerprint = "owner-command",
            createdAt = at,
        )
        val paused = GoalPlanReducer().apply(GoalPlanRuntimeState.initial(plan), pausedTransition).state

        val result = GoalStepDecisionProjector().project(
            paused,
            step.id,
            decision(ConvergenceDecisionState.ACTIONABLE, "later-actionable"),
            at.plusSeconds(1),
        )

        assertEquals(
            GoalStepDecisionProjectionResult.Unchanged(
                state = GoalStepState.PAUSED,
                reason = "state-not-convergence-mutable:PAUSED",
            ),
            result,
        )
    }

    @Test
    fun `action intent is restart stable and bound to plan step and V5 decision`() {
        val plan = plan()
        val step = plan.steps.first()
        val decision = decision(ConvergenceDecisionState.ACTIONABLE, "actionable")
        val readyTransition = assertIs<GoalStepDecisionProjectionResult.Transitioned>(
            GoalStepDecisionProjector().project(
                GoalPlanRuntimeState.initial(plan),
                step.id,
                decision,
                at,
            )
        ).transition
        val ready = GoalPlanReducer().apply(GoalPlanRuntimeState.initial(plan), readyTransition).state
        val restored = GoalPlanReducer().replay(plan, listOf(readyTransition))
        val factory = GoalActionIntentFactory()

        val beforeRestart = factory.create(ready, step.id, decision)
        val afterRestart = factory.create(restored, step.id, decision)

        assertEquals(beforeRestart, afterRestart)
        assertEquals(beforeRestart.actionId.value, beforeRestart.idempotencyKey)
        assertEquals(plan.sourceGoalPhotonId, beforeRestart.sourceGoalPhotonId)
        assertEquals(plan.sourceGoalPhotonRevision, beforeRestart.sourceGoalPhotonRevision)
    }

    @Test
    fun `action intent cannot be created before readiness or from non actionable decision`() {
        val state = GoalPlanRuntimeState.initial(plan())
        val step = state.definition.steps.first()
        val factory = GoalActionIntentFactory()

        assertFailsWith<IllegalArgumentException> {
            factory.create(state, step.id, decision(ConvergenceDecisionState.ACTIONABLE, "too-early"))
        }

        val readyTransition = GoalPlanTransition.create(
            planId = state.definition.id,
            predecessorId = null,
            stepId = step.id,
            fromState = GoalStepState.PLANNED,
            toState = GoalStepState.READY,
            reason = "manual-ready-test",
            sourceFingerprint = "source",
            createdAt = at,
        )
        val ready = GoalPlanReducer().apply(state, readyTransition).state
        assertFailsWith<IllegalArgumentException> {
            factory.create(ready, step.id, decision(ConvergenceDecisionState.EVIDENCE_REQUIRED, "blocked"))
        }
    }

    private fun plan(): GoalPlanDefinition = GoalPlanDefinition.create(
        sourceGoalPhotonId = PhotonId("goal-source"),
        sourceGoalPhotonRevision = 4L,
        stepSpecs = listOf(GoalStepSpec("step", "Do durable work")),
        createdAt = at.minusSeconds(10),
    )

    private fun decision(state: ConvergenceDecisionState, suffix: String): ConvergenceDecision =
        ConvergenceDecision(
            id = ConvergenceDecisionId("convergence-decision-$suffix"),
            state = state,
            selectedHypothesisIds = if (state == ConvergenceDecisionState.ACTIONABLE) {
                listOf(HypothesisId("hypothesis-$suffix"))
            } else {
                emptyList()
            },
            candidates = emptyList(),
            evidenceRequests = emptyList(),
            capabilityGaps = emptyList(),
            escalation = null,
            reasons = listOf("test-$suffix"),
            sourceFingerprint = "source-fingerprint-$suffix",
        )
}
