package app.lifeos.next.ui.goals

import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.goal.GoalPlanDefinition
import app.lifeos.core.runtime.goal.GoalPlanId
import app.lifeos.core.runtime.goal.GoalPlanRuntimeState
import app.lifeos.core.runtime.goal.GoalStepSpec
import app.lifeos.core.runtime.goal.GoalStepState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalWorkspaceProjectorTest {
    private val now = Instant.parse("2026-09-13T20:00:00Z")

    @Test
    fun projectsRunningPlanAsActiveAndPreservesExactProgress() {
        val definition = twoStepPlan("running", now.minusSeconds(300))
        val state = runtimeState(
            definition,
            mapOf(
                "action:search" to GoalStepState.COMPLETED,
                "verify:outcome" to GoalStepState.RUNNING,
            ),
        )

        val projected = GoalWorkspaceProjector.project(mapOf(definition.id to state), now)
            .plans.single()

        assertEquals(GoalPlanUiStatus.ACTIVE, projected.status)
        assertEquals("Find exact evidence", projected.title)
        assertEquals(1, projected.completedSteps)
        assertEquals(2, projected.totalSteps)
        assertEquals(
            definition.steps.single { it.key == "verify:outcome" }.id,
            projected.currentStepId,
        )
        assertNull(projected.nextStepId)
        assertEquals(
            GoalStepPresentationKind.OWNER_ACTION,
            projected.steps.single { it.key == "action:search" }.presentationKind,
        )
        assertEquals(
            GoalStepPresentationKind.INTERNAL_VERIFICATION,
            projected.steps.single { it.key == "verify:outcome" }.presentationKind,
        )
    }

    @Test
    fun usesNextActionDeriverForDependenciesAndExpiredSteps() {
        val definition = GoalPlanDefinition.create(
            sourceGoalPhotonId = PhotonId("goal-derivation"),
            sourceGoalPhotonRevision = 1L,
            stepSpecs = listOf(
                GoalStepSpec(
                    key = "action:search",
                    objective = "Collect evidence",
                    priority = 100,
                ),
                GoalStepSpec(
                    key = "verify:outcome",
                    objective = "Verify persisted outcome for search",
                    dependencyKeys = setOf("action:search"),
                    deadline = now.minusSeconds(1),
                    priority = 50,
                ),
                GoalStepSpec(
                    key = "future:dependent",
                    objective = "Future dependent step",
                    dependencyKeys = setOf("verify:outcome"),
                    priority = 25,
                ),
            ),
            createdAt = now.minusSeconds(100),
        )
        val state = runtimeState(
            definition,
            mapOf("action:search" to GoalStepState.COMPLETED),
        )

        val projected = GoalWorkspaceProjector.project(mapOf(definition.id to state), now)
            .plans.single()
        val verify = projected.steps.single { it.key == "verify:outcome" }
        val future = projected.steps.single { it.key == "future:dependent" }

        assertTrue(verify.expired)
        assertNull(projected.nextStepId)
        assertEquals(
            listOf(definition.steps.single { it.key == "verify:outcome" }.id),
            future.unmetDependencyIds,
        )
        assertEquals(GoalStepPresentationKind.OTHER, future.presentationKind)
    }

    @Test
    fun runningStateWinsPlanStatusPrecedence() {
        val definition = GoalPlanDefinition.create(
            sourceGoalPhotonId = PhotonId("goal-precedence"),
            sourceGoalPhotonRevision = 1L,
            stepSpecs = listOf(
                GoalStepSpec("running", "Running work", priority = 100),
                GoalStepSpec("replan", "Needs replan", priority = 50),
                GoalStepSpec("failed", "Failed work", priority = 25),
            ),
            createdAt = now,
        )
        val state = runtimeState(
            definition,
            mapOf(
                "running" to GoalStepState.RUNNING,
                "replan" to GoalStepState.REPLAN_REQUIRED,
                "failed" to GoalStepState.FAILED,
            ),
        )

        val projected = GoalWorkspaceProjector.project(mapOf(definition.id to state), now)
            .plans.single()

        assertEquals(GoalPlanUiStatus.ACTIVE, projected.status)
        assertEquals(
            definition.steps.single { it.key == "running" }.id,
            projected.currentStepId,
        )
    }

    @Test
    fun terminalPlansMapToCompletedAndCancelled() {
        val completedDefinition = twoStepPlan("completed", now.minusSeconds(20))
        val cancelledDefinition = twoStepPlan("cancelled", now.minusSeconds(10))
        val completed = runtimeState(
            completedDefinition,
            completedDefinition.steps.associate { it.key to GoalStepState.COMPLETED },
        )
        val cancelled = runtimeState(
            cancelledDefinition,
            cancelledDefinition.steps.associate { it.key to GoalStepState.CANCELLED },
        )

        val plans = GoalWorkspaceProjector.project(
            mapOf(
                cancelledDefinition.id to cancelled,
                completedDefinition.id to completed,
            ),
            now,
        ).plans

        assertEquals(GoalPlanUiStatus.COMPLETED, plans[0].status)
        assertEquals(GoalPlanUiStatus.CANCELLED, plans[1].status)
        assertEquals(2, plans[0].completedSteps)
        assertEquals(0, plans[1].completedSteps)
    }

    @Test
    fun sortsByStatusThenRecencyAndStableId() {
        val olderActiveDefinition = twoStepPlan("older-active", now.minusSeconds(200))
        val newerActiveDefinition = twoStepPlan("newer-active", now.minusSeconds(100))
        val waitingDefinition = twoStepPlan("waiting", now)
        val states = mapOf(
            waitingDefinition.id to runtimeState(
                waitingDefinition,
                mapOf("action:search" to GoalStepState.WAITING_EVIDENCE),
            ),
            olderActiveDefinition.id to runtimeState(
                olderActiveDefinition,
                mapOf("action:search" to GoalStepState.READY),
            ),
            newerActiveDefinition.id to runtimeState(
                newerActiveDefinition,
                mapOf("action:search" to GoalStepState.READY),
            ),
        )

        val plans = GoalWorkspaceProjector.project(states, now).plans

        assertEquals(newerActiveDefinition.id, plans[0].id)
        assertEquals(olderActiveDefinition.id, plans[1].id)
        assertEquals(waitingDefinition.id, plans[2].id)
    }

    @Test
    fun projectionIsDeterministicAndDoesNotMutateRuntimeState() {
        val definition = twoStepPlan("stable", now.minusSeconds(60))
        val state = runtimeState(
            definition,
            mapOf("action:search" to GoalStepState.READY),
        )
        val before = state.copy(
            history = state.history.toList(),
            stepStates = state.stepStates.toMap(),
            activeActionIds = state.activeActionIds.toMap(),
            outcomePhotonIds = state.outcomePhotonIds.toMap(),
        )

        val first = GoalWorkspaceProjector.project(mapOf(definition.id to state), now)
        val second = GoalWorkspaceProjector.project(mapOf(definition.id to state), now)

        assertEquals(first, second)
        assertEquals(before, state)
        assertFalse(first.plans.single().steps.any { it.expired })
    }

    private fun twoStepPlan(suffix: String, createdAt: Instant): GoalPlanDefinition =
        GoalPlanDefinition.create(
            sourceGoalPhotonId = PhotonId("goal-$suffix"),
            sourceGoalPhotonRevision = 1L,
            stepSpecs = listOf(
                GoalStepSpec(
                    key = "action:search",
                    objective = "Find exact evidence",
                    priority = 100,
                ),
                GoalStepSpec(
                    key = "verify:outcome",
                    objective = "Verify persisted outcome for search",
                    dependencyKeys = setOf("action:search"),
                    priority = 50,
                ),
            ),
            createdAt = createdAt,
        )

    private fun runtimeState(
        definition: GoalPlanDefinition,
        statesByKey: Map<String, GoalStepState>,
    ): GoalPlanRuntimeState {
        val stepStates = definition.steps.associate { step ->
            step.id to statesByKey.getOrDefault(step.key, GoalStepState.PLANNED)
        }
        val activeActions = definition.steps
            .filter { stepStates.getValue(it.id) == GoalStepState.RUNNING }
            .associate { it.id to "active-${it.key}" }
        val outcomes = definition.steps
            .filter {
                val state = stepStates.getValue(it.id)
                state == GoalStepState.COMPLETED || state == GoalStepState.FAILED
            }
            .associate { it.id to PhotonId("outcome-${it.key}") }
        return GoalPlanRuntimeState(
            definition = definition,
            revision = 0L,
            headTransitionId = null,
            history = emptyList(),
            stepStates = stepStates,
            activeActionIds = activeActions,
            outcomePhotonIds = outcomes,
        )
    }
}
