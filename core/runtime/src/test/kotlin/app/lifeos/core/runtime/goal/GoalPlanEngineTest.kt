package app.lifeos.core.runtime.goal

import app.lifeos.core.model.PhotonId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GoalPlanEngineTest {
    private val at = Instant.parse("2026-09-11T10:00:00Z")

    @Test
    fun `plan identity and step ids are canonical across input order`() {
        val first = GoalPlanDefinition.create(
            sourceGoalPhotonId = PhotonId("goal-source"),
            sourceGoalPhotonRevision = 3L,
            stepSpecs = listOf(
                GoalStepSpec("collect", "Collect evidence", priority = 2),
                GoalStepSpec("act", "Execute action", dependencyKeys = setOf("collect"), priority = 1),
            ),
            createdAt = at,
        )
        val second = GoalPlanDefinition.create(
            sourceGoalPhotonId = PhotonId("goal-source"),
            sourceGoalPhotonRevision = 3L,
            stepSpecs = listOf(
                GoalStepSpec("act", "Execute action", dependencyKeys = linkedSetOf("collect"), priority = 1),
                GoalStepSpec("collect", "Collect evidence", priority = 2),
            ),
            createdAt = at,
        )

        assertEquals(first.id, second.id)
        assertEquals(first.steps, second.steps)
        assertEquals(first.contentFingerprint(), second.contentFingerprint())
    }

    @Test
    fun `unknown and cyclic dependencies fail closed`() {
        assertFailsWith<IllegalArgumentException> {
            GoalPlanDefinition.create(
                sourceGoalPhotonId = PhotonId("goal-source"),
                sourceGoalPhotonRevision = 1L,
                stepSpecs = listOf(GoalStepSpec("a", "A", dependencyKeys = setOf("missing"))),
                createdAt = at,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            GoalPlanDefinition.create(
                sourceGoalPhotonId = PhotonId("goal-source"),
                sourceGoalPhotonRevision = 1L,
                stepSpecs = listOf(
                    GoalStepSpec("a", "A", dependencyKeys = setOf("b")),
                    GoalStepSpec("b", "B", dependencyKeys = setOf("a")),
                ),
                createdAt = at,
            )
        }
    }

    @Test
    fun `append only replay reconstructs exact state independent of physical event order`() {
        val plan = plan()
        val step = plan.steps.single { it.key == "first" }
        val ready = transition(
            plan = plan,
            predecessor = null,
            step = step,
            from = GoalStepState.PLANNED,
            to = GoalStepState.READY,
            second = 1,
        )
        val running = transition(
            plan = plan,
            predecessor = ready.id,
            step = step,
            from = GoalStepState.READY,
            to = GoalStepState.RUNNING,
            second = 2,
            actionId = "action-first",
            idempotencyKey = "goal:${plan.id.value}:step:${step.id.value}:v1",
        )
        val completed = transition(
            plan = plan,
            predecessor = running.id,
            step = step,
            from = GoalStepState.RUNNING,
            to = GoalStepState.COMPLETED,
            second = 3,
            actionId = "action-first",
            outcomePhotonId = PhotonId("outcome:first"),
        )
        val reducer = GoalPlanReducer()
        val sequential = listOf(ready, running, completed).fold(GoalPlanRuntimeState.initial(plan)) { state, event ->
            reducer.apply(state, event).state
        }
        val recovered = reducer.replay(plan, listOf(completed, ready, running))

        assertEquals(sequential, recovered)
        assertEquals(3L, recovered.revision)
        assertEquals(GoalStepState.COMPLETED, recovered.stepStates.getValue(step.id))
        assertEquals(PhotonId("outcome:first"), recovered.outcomePhotonIds.getValue(step.id))
        assertTrue(reducer.apply(recovered, completed).replayed)
    }

    @Test
    fun `terminal outcome must close the exact active action`() {
        val plan = plan()
        val step = plan.steps.single { it.key == "first" }
        val reducer = GoalPlanReducer()
        val ready = transition(plan, null, step, GoalStepState.PLANNED, GoalStepState.READY, 1)
        val running = transition(
            plan,
            ready.id,
            step,
            GoalStepState.READY,
            GoalStepState.RUNNING,
            2,
            actionId = "action-first",
            idempotencyKey = "stable-action-key",
        )
        val runningState = reducer.apply(reducer.apply(GoalPlanRuntimeState.initial(plan), ready).state, running).state
        val wrongOutcome = transition(
            plan,
            running.id,
            step,
            GoalStepState.RUNNING,
            GoalStepState.COMPLETED,
            3,
            actionId = "different-action",
            outcomePhotonId = PhotonId("outcome:wrong"),
        )

        assertFailsWith<IllegalArgumentException> {
            reducer.apply(runningState, wrongOutcome)
        }
    }

    @Test
    fun `next action derivation respects dependencies and reports expired work explicitly`() {
        val plan = GoalPlanDefinition.create(
            sourceGoalPhotonId = PhotonId("goal-source"),
            sourceGoalPhotonRevision = 1L,
            stepSpecs = listOf(
                GoalStepSpec("first", "First", priority = 5),
                GoalStepSpec("second", "Second", dependencyKeys = setOf("first"), priority = 4),
                GoalStepSpec("expired", "Expired", deadline = at.minusSeconds(1), priority = 10),
            ),
            createdAt = at.minusSeconds(10),
        )
        val first = plan.steps.single { it.key == "first" }
        val second = plan.steps.single { it.key == "second" }
        val expired = plan.steps.single { it.key == "expired" }
        val deriver = GoalNextActionDeriver()
        val initial = deriver.derive(GoalPlanRuntimeState.initial(plan), at)

        assertEquals(listOf(first.id), initial.readyStepIds)
        assertEquals(listOf(first.id), initial.blockedByDependencies.getValue(second.id))
        assertEquals(listOf(expired.id), initial.expiredStepIds)

        val reducer = GoalPlanReducer()
        val ready = transition(plan, null, first, GoalStepState.PLANNED, GoalStepState.READY, 1)
        val running = transition(
            plan,
            ready.id,
            first,
            GoalStepState.READY,
            GoalStepState.RUNNING,
            2,
            actionId = "action-first",
            idempotencyKey = "stable-first",
        )
        val completed = transition(
            plan,
            running.id,
            first,
            GoalStepState.RUNNING,
            GoalStepState.COMPLETED,
            3,
            actionId = "action-first",
            outcomePhotonId = PhotonId("outcome:first"),
        )
        val progressed = reducer.replay(plan, listOf(completed, running, ready))
        val after = deriver.derive(progressed, at)

        assertEquals(listOf(second.id), after.readyStepIds)
        assertTrue(after.blockedByDependencies.isEmpty())
        assertEquals(listOf(expired.id), after.expiredStepIds)
    }

    @Test
    fun `waiting for evidence requires explicit decision lineage`() {
        val plan = plan()
        val step = plan.steps.single { it.key == "first" }
        assertFailsWith<IllegalArgumentException> {
            transition(
                plan,
                null,
                step,
                GoalStepState.PLANNED,
                GoalStepState.WAITING_EVIDENCE,
                1,
            )
        }
    }

    private fun plan(): GoalPlanDefinition = GoalPlanDefinition.create(
        sourceGoalPhotonId = PhotonId("goal-source"),
        sourceGoalPhotonRevision = 1L,
        stepSpecs = listOf(
            GoalStepSpec("first", "First step"),
            GoalStepSpec("second", "Second step", dependencyKeys = setOf("first")),
        ),
        createdAt = at,
    )

    private fun transition(
        plan: GoalPlanDefinition,
        predecessor: GoalTransitionId?,
        step: GoalStepDefinition,
        from: GoalStepState,
        to: GoalStepState,
        second: Long,
        actionId: String? = null,
        idempotencyKey: String? = null,
        outcomePhotonId: PhotonId? = null,
        decisionFingerprint: String? = null,
    ): GoalPlanTransition = GoalPlanTransition.create(
        planId = plan.id,
        predecessorId = predecessor,
        stepId = step.id,
        fromState = from,
        toState = to,
        reason = "test-$from-$to",
        sourceFingerprint = "source-$second",
        decisionFingerprint = decisionFingerprint,
        actionId = actionId,
        actionIdempotencyKey = idempotencyKey,
        outcomePhotonId = outcomePhotonId,
        createdAt = at.plusSeconds(second),
    )
}
