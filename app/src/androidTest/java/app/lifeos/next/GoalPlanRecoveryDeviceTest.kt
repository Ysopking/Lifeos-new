package app.lifeos.next

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.goal.GoalNextActionDeriver
import app.lifeos.core.runtime.goal.GoalPlanDefinition
import app.lifeos.core.runtime.goal.GoalPlanId
import app.lifeos.core.runtime.goal.GoalPlanTransition
import app.lifeos.core.runtime.goal.GoalStepSpec
import app.lifeos.core.runtime.goal.GoalStepState
import app.lifeos.next.kernel.KernelBootstrapStatus
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the production kernel ledger across the force-stop boundary in the recovery script. */
@RunWith(AndroidJUnit4::class)
class GoalPlanRecoveryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app: LifeOsApplication
        get() = instrumentation.targetContext.applicationContext as LifeOsApplication
    private val marker
        get() = instrumentation.targetContext.filesDir.resolve("v7-goal-plan-recovery.marker")
    private val at = Instant.parse("2026-09-11T14:00:00Z")

    private suspend fun awaitBoot() {
        val processStartup = withTimeoutOrNull(90_000) {
            app.startupState.first { state ->
                state.phase == LifeOsProcessStartupPhase.READY ||
                    state.phase == LifeOsProcessStartupPhase.FAILED
            }
        } ?: error(
            "Process startup timed out during V7 recovery at stage: " +
                app.startupState.value.stage +
                "; kernel boot state=" + app.kernel.bootProgress.value.name
        )
        if (processStartup.phase == LifeOsProcessStartupPhase.FAILED) {
            error("Process startup failed during V7 recovery: ${processStartup.failure ?: "unknown"}")
        }
        val boot = withTimeoutOrNull(90_000) {
            app.kernel.bootstrapState.first {
                it.status == KernelBootstrapStatus.READY ||
                    it.status == KernelBootstrapStatus.DEGRADED ||
                    it.status == KernelBootstrapStatus.FAILED
            }
        } ?: error(
            "Kernel boot timed out during V7 recovery: " +
                app.kernel.bootstrapState.value.status
        )
        if (boot.status == KernelBootstrapStatus.FAILED) {
            error("Kernel boot failed during V7 recovery: ${boot.failureMessage ?: "unknown"}")
        }
        assertTrue("Kernel must restore V7 before runtime start", boot.ready)
    }

    @Test
    fun seedGoalPlanProgress() = runBlocking {
        awaitBoot()
        val ledger = app.kernel.goalPlans
        val plan = GoalPlanDefinition.create(
            sourceGoalPhotonId = PhotonId("android-v7-goal"),
            sourceGoalPhotonRevision = 1L,
            stepSpecs = listOf(
                GoalStepSpec("first", "Produce evidence"),
                GoalStepSpec("second", "Consume evidence", dependencyKeys = setOf("first")),
                GoalStepSpec("waiting", "Wait for more evidence"),
                GoalStepSpec("paused", "Respect owner pause"),
            ),
            createdAt = at,
        )
        ledger.create(plan)
        var predecessor: app.lifeos.core.runtime.goal.GoalTransitionId? = null
        var sequence = 0L
        suspend fun append(key: String, from: GoalStepState, to: GoalStepState) {
            sequence++
            val transition = GoalPlanTransition.create(
                planId = plan.id,
                predecessorId = predecessor,
                stepId = plan.steps.single { it.key == key }.id,
                fromState = from,
                toState = to,
                reason = "android-process-restart-proof",
                sourceFingerprint = "android-v7-source",
                decisionFingerprint = "android-v7-decision",
                actionId = if (to == GoalStepState.RUNNING || to == GoalStepState.COMPLETED) "action-$key" else null,
                actionIdempotencyKey = if (to == GoalStepState.RUNNING) "stable-$key" else null,
                outcomePhotonId = if (to == GoalStepState.COMPLETED) PhotonId("outcome-$key") else null,
                createdAt = at.plusSeconds(sequence),
            )
            ledger.append(transition)
            predecessor = transition.id
        }
        append("first", GoalStepState.PLANNED, GoalStepState.READY)
        append("first", GoalStepState.READY, GoalStepState.RUNNING)
        append("first", GoalStepState.RUNNING, GoalStepState.COMPLETED)
        append("second", GoalStepState.PLANNED, GoalStepState.READY)
        append("second", GoalStepState.READY, GoalStepState.RUNNING)
        append("waiting", GoalStepState.PLANNED, GoalStepState.WAITING_EVIDENCE)
        append("paused", GoalStepState.PLANNED, GoalStepState.PAUSED)
        val state = requireNotNull(ledger.state(plan.id))
        marker.writeText(
            (listOf(plan.id.value, state.revision.toString()) + state.history.map { it.id.value })
                .joinToString("\n")
        )
        assertEquals(7L, state.revision)
    }

    @Test
    fun recoverGoalPlanAfterColdStart() = runBlocking {
        awaitBoot()
        assertTrue("V7 marker must survive process death", marker.isFile)
        val expected = marker.readLines()
        assertEquals(9, expected.size)
        // Inspect the kernel's boot-restored state; do not hide missing wiring with manual rehydrate.
        val ledger = app.kernel.goalPlans
        val state = requireNotNull(ledger.state(GoalPlanId(expected[0])))
        assertEquals(expected[1].toLong(), state.revision)
        assertEquals(expected.drop(2), state.history.map { it.id.value })
        fun step(key: String) = state.definition.steps.single { it.key == key }.id
        assertEquals(GoalStepState.COMPLETED, state.stepStates[step("first")])
        assertEquals(PhotonId("outcome-first"), state.outcomePhotonIds[step("first")])
        assertEquals(GoalStepState.RUNNING, state.stepStates[step("second")])
        assertEquals("action-second", state.activeActionIds[step("second")])
        assertEquals(GoalStepState.WAITING_EVIDENCE, state.stepStates[step("waiting")])
        assertEquals(GoalStepState.PAUSED, state.stepStates[step("paused")])
        assertTrue(GoalNextActionDeriver().derive(state, at.plusSeconds(60)).readyStepIds.isEmpty())
        assertTrue(ledger.append(state.history.last()).replayed)
        assertEquals(state, ledger.state(state.definition.id))
    }
}
