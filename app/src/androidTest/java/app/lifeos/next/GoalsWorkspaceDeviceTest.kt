package app.lifeos.next

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lifeos.core.runtime.goal.GoalPlanId
import app.lifeos.core.runtime.goal.GoalStepState
import app.lifeos.next.kernel.KernelBootstrapStatus
import app.lifeos.next.ui.goals.GoalPlanUiStatus
import app.lifeos.next.ui.goals.GoalWorkspaceProjector
import app.lifeos.next.ui.goals.TodayPlanProjector
import app.lifeos.next.ui.goals.TodayPlanTiming
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only F6 proof over the productive V7 durable ledger used by recovery CI. */
@RunWith(AndroidJUnit4::class)
class GoalsWorkspaceDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app: LifeOsApplication
        get() = instrumentation.targetContext.applicationContext as LifeOsApplication
    private val marker
        get() = instrumentation.targetContext.filesDir.resolve("v7-goal-plan-recovery.marker")
    private val projectionAt = Instant.parse("2026-09-11T14:01:00Z")

    @Test
    fun productiveLedgerProjectsWithoutGoalMutation() = runBlocking {
        awaitBoot()
        val before = fixtureState()

        val workspace = GoalWorkspaceProjector.project(
            states = app.kernel.goalPlans.states.value,
            at = projectionAt,
        )
        val projected = workspace.plans.single { it.id == before.definition.id }
        val today = TodayPlanProjector.project(workspace, projectionAt, ZoneOffset.UTC)
        val currentToday = today.items.single {
            it.planId == projected.id && it.stepId == projected.currentStepId
        }

        assertEquals(GoalPlanUiStatus.ACTIVE, projected.status)
        assertEquals(1, projected.completedSteps)
        assertEquals(4, projected.totalSteps)
        assertEquals(
            before.definition.steps.single { it.key == "second" }.id,
            projected.currentStepId,
        )
        assertNullNextAction(projected.nextStepId)
        assertEquals(
            GoalStepState.WAITING_EVIDENCE,
            projected.steps.single { it.key == "waiting" }.state,
        )
        assertEquals(
            GoalStepState.PAUSED,
            projected.steps.single { it.key == "paused" }.state,
        )
        assertEquals(TodayPlanTiming.NOW, currentToday.timing)
        assertEquals(before, fixtureState())
    }

    @Test
    fun recoveredGoalWorkspaceMatchesDurableLedgerAfterColdStart() = runBlocking {
        awaitBoot()
        val before = fixtureState()
        val first = GoalWorkspaceProjector.project(
            states = app.kernel.goalPlans.states.value,
            at = projectionAt,
        )
        val second = GoalWorkspaceProjector.project(
            states = app.kernel.goalPlans.states.value,
            at = projectionAt,
        )
        val firstToday = TodayPlanProjector.project(first, projectionAt, ZoneOffset.UTC)
        val secondToday = TodayPlanProjector.project(second, projectionAt, ZoneOffset.UTC)
        val projected = first.plans.single { it.id == before.definition.id }
        val currentToday = firstToday.items.single {
            it.planId == projected.id && it.stepId == projected.currentStepId
        }

        assertEquals(first, second)
        assertEquals(firstToday, secondToday)
        assertEquals(TodayPlanTiming.NOW, currentToday.timing)
        assertEquals(before.definition.id, projected.id)
        assertEquals(before.revision, projected.revision)
        assertEquals(before.definition.sourceGoalPhotonId, projected.sourceGoalPhotonId)
        assertEquals(before.definition.sourceGoalPhotonRevision, projected.sourceGoalPhotonRevision)
        assertEquals(before.outcomePhotonIds.values.toSet(), projected.steps.mapNotNull { it.outcomePhotonId }.toSet())
        assertEquals(before.stepStates.keys, projected.steps.mapTo(mutableSetOf()) { it.id })
        assertTrue(projected.steps.any { it.state == GoalStepState.RUNNING })
        assertFalse(projected.steps.any { it.expired })
        assertEquals(before, fixtureState())
    }

    private suspend fun awaitBoot() {
        val processStartup = withTimeout(30_000) {
            app.startupState.first { state ->
                state.phase == LifeOsProcessStartupPhase.READY ||
                    state.phase == LifeOsProcessStartupPhase.FAILED
            }
        }
        if (processStartup.phase == LifeOsProcessStartupPhase.FAILED) {
            error("Process startup failed before Goals workspace proof: ${processStartup.failure ?: "unknown"}")
        }
        val boot = withTimeout(30_000) {
            app.kernel.bootstrapState.first {
                it.status == KernelBootstrapStatus.READY ||
                    it.status == KernelBootstrapStatus.DEGRADED ||
                    it.status == KernelBootstrapStatus.FAILED
            }
        }
        if (boot.status == KernelBootstrapStatus.FAILED) {
            error("Kernel boot failed before Goals workspace proof: ${boot.failureMessage ?: "unknown"}")
        }
        assertTrue("Kernel must restore goal plans before Goals workspace proof", boot.ready)
    }

    private fun fixtureState(): app.lifeos.core.runtime.goal.GoalPlanRuntimeState {
        assertTrue("Goal recovery marker must exist before Goals workspace proof", marker.isFile)
        val expected = marker.readLines()
        assertTrue("Goal recovery marker must contain plan id and revision", expected.size >= 2)
        val planId = GoalPlanId(expected[0])
        val state = app.kernel.goalPlans.state(planId)
        assertNotNull("Goal recovery fixture must exist in productive ledger", state)
        state!!
        assertEquals(expected[1].toLong(), state.revision)
        return state
    }

    private fun assertNullNextAction(value: app.lifeos.core.runtime.goal.GoalStepId?) {
        assertEquals(null, value)
    }
}
