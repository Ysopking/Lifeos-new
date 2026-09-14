package app.lifeos.next.ui.goals

import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.goal.GoalPlanId
import app.lifeos.core.runtime.goal.GoalStepId
import app.lifeos.core.runtime.goal.GoalStepState
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayPlanProjectorTest {
    private val at = Instant.parse("2026-09-14T10:00:00Z")
    private val utc = ZoneId.of("UTC")

    @Test
    fun ordersOverdueThenCurrentThenTodayThenReadyWithoutInventingDeadlines() {
        val overdue = step(
            id = stepId('a'),
            objective = "Overdue evidence",
            state = GoalStepState.WAITING_EVIDENCE,
            deadline = Instant.parse("2026-09-13T09:00:00Z"),
            priority = 10,
        )
        val running = step(
            id = stepId('b'),
            objective = "Running now",
            state = GoalStepState.RUNNING,
            deadline = Instant.parse("2026-09-15T09:00:00Z"),
            priority = 20,
        )
        val dueToday = step(
            id = stepId('c'),
            objective = "Due today",
            state = GoalStepState.WAITING_CAPABILITY,
            deadline = Instant.parse("2026-09-14T18:00:00Z"),
            priority = 30,
        )
        val ready = step(
            id = stepId('d'),
            objective = "Ready owner action",
            state = GoalStepState.READY,
            deadline = null,
            priority = 40,
            kind = GoalStepPresentationKind.OWNER_ACTION,
        )
        val plan = plan(
            id = planId('a'),
            steps = listOf(overdue, running, dueToday, ready),
            currentStepId = running.id,
            nextStepId = ready.id,
        )

        val today = TodayPlanProjector.project(GoalWorkspaceUiModel(listOf(plan)), at, utc)

        assertEquals(
            listOf(
                TodayPlanTiming.OVERDUE,
                TodayPlanTiming.NOW,
                TodayPlanTiming.TODAY,
                TodayPlanTiming.UNSCHEDULED,
            ),
            today.items.map { it.timing },
        )
        assertEquals("Ready owner action", today.items.last().objective)
        assertNull(today.items.last().deadline)
        assertEquals(1, today.overdueCount)
        assertEquals(2, today.actionableCount)
    }

    @Test
    fun excludesTerminalPlansTerminalStepsAndUnreadyFutureWork() {
        val future = step(
            id = stepId('a'),
            objective = "Future planned work",
            state = GoalStepState.PLANNED,
            deadline = Instant.parse("2026-09-15T18:00:00Z"),
            kind = GoalStepPresentationKind.OWNER_ACTION,
        )
        val completed = step(
            id = stepId('b'),
            objective = "Completed today",
            state = GoalStepState.COMPLETED,
            deadline = Instant.parse("2026-09-14T09:00:00Z"),
            kind = GoalStepPresentationKind.OWNER_ACTION,
        )
        val active = plan(planId('a'), listOf(future, completed))
        val terminal = plan(
            id = planId('b'),
            steps = listOf(
                step(
                    id = stepId('c'),
                    objective = "Terminal plan item",
                    state = GoalStepState.READY,
                    kind = GoalStepPresentationKind.OWNER_ACTION,
                )
            ),
            status = GoalPlanUiStatus.COMPLETED,
        )

        val today = TodayPlanProjector.project(
            GoalWorkspaceUiModel(listOf(active, terminal)),
            at,
            utc,
        )

        assertTrue(today.items.isEmpty())
    }

    @Test
    fun dueDateUsesProvidedZoneAndBlockedDependenciesRemainVisibleButNotActionable() {
        val deadline = Instant.parse("2026-09-14T22:30:00Z")
        val blocked = step(
            id = stepId('a'),
            objective = "Blocked tonight",
            state = GoalStepState.BLOCKED,
            deadline = deadline,
            unmetDependencies = listOf(stepId('f')),
        )
        val plan = plan(planId('a'), listOf(blocked))

        val berlin = TodayPlanProjector.project(
            GoalWorkspaceUiModel(listOf(plan)),
            at = Instant.parse("2026-09-14T20:00:00Z"),
            zoneId = ZoneId.of("Europe/Berlin"),
        )
        val utcProjection = TodayPlanProjector.project(
            GoalWorkspaceUiModel(listOf(plan)),
            at = Instant.parse("2026-09-14T20:00:00Z"),
            zoneId = utc,
        )

        assertTrue(berlin.items.isEmpty())
        assertEquals(1, utcProjection.items.size)
        assertTrue(utcProjection.items.single().blockedByDependencies)
        assertEquals(0, utcProjection.actionableCount)
    }

    @Test
    fun projectionIsDeterministicAndLeavesWorkspaceUntouched() {
        val ready = step(
            id = stepId('a'),
            objective = "Stable ready action",
            state = GoalStepState.READY,
            priority = 99,
            kind = GoalStepPresentationKind.OWNER_ACTION,
        )
        val workspace = GoalWorkspaceUiModel(listOf(plan(planId('a'), listOf(ready), nextStepId = ready.id)))
        val before = workspace.copy(plans = workspace.plans.map { it.copy(steps = it.steps.toList()) })

        val first = TodayPlanProjector.project(workspace, at, utc)
        val second = TodayPlanProjector.project(workspace, at, utc)

        assertEquals(first, second)
        assertEquals(before, workspace)
        assertFalse(first.items.single().blockedByDependencies)
    }

    private fun plan(
        id: GoalPlanId,
        steps: List<GoalStepUiModel>,
        status: GoalPlanUiStatus = GoalPlanUiStatus.ACTIVE,
        currentStepId: GoalStepId? = null,
        nextStepId: GoalStepId? = null,
    ): GoalPlanUiModel = GoalPlanUiModel(
        id = id,
        title = "Plan ${id.value.takeLast(4)}",
        status = status,
        createdAt = at.minusSeconds(3_600),
        lastActivityAt = at.minusSeconds(60),
        revision = 1L,
        completedSteps = steps.count { it.state == GoalStepState.COMPLETED },
        totalSteps = steps.size,
        currentStepId = currentStepId,
        nextStepId = nextStepId,
        sourceGoalPhotonId = PhotonId("source-${id.value.takeLast(4)}"),
        sourceGoalPhotonRevision = 1L,
        steps = steps,
    )

    private fun step(
        id: GoalStepId,
        objective: String,
        state: GoalStepState,
        deadline: Instant? = null,
        priority: Int = 0,
        kind: GoalStepPresentationKind = GoalStepPresentationKind.OTHER,
        unmetDependencies: List<GoalStepId> = emptyList(),
    ): GoalStepUiModel = GoalStepUiModel(
        id = id,
        key = "key-${id.value.takeLast(4)}",
        objective = objective,
        presentationKind = kind,
        state = state,
        deadline = deadline,
        priority = priority,
        dependencyIds = unmetDependencies,
        unmetDependencyIds = unmetDependencies,
        expired = deadline?.isBefore(at) == true,
        activeActionId = if (state == GoalStepState.RUNNING) "active-${id.value.takeLast(4)}" else null,
        outcomePhotonId = null,
    )

    private fun planId(hex: Char): GoalPlanId = GoalPlanId("goal-plan:${hex.toString().repeat(64)}")

    private fun stepId(hex: Char): GoalStepId = GoalStepId("goal-step:${hex.toString().repeat(64)}")
}
