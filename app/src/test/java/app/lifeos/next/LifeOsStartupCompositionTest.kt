package app.lifeos.next

import app.lifeos.core.runtime.topology.LifeOsProcessTopology
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest

class LifeOsStartupCompositionTest {
    @Test
    fun `critical startup ends at UI ready without entering warm lane`() = runTest {
        val actions = mutableListOf<String>()
        val events = mutableListOf<LifeOsStartupStageEvent>()

        LifeOsStartupComposition.startCritical(hooks(actions, events))

        assertEquals(
            listOf("shared-resources", "goal-execution", "kernel-created", "kernel-start", "cognitive-ready"),
            actions,
        )
        val completed = events.filterIsInstance<LifeOsStartupStageEvent.Completed>()
        assertEquals(
            listOf(
                LifeOsStartupStage.SHARED_RESOURCES,
                LifeOsStartupStage.GOAL_EXECUTION,
                LifeOsStartupStage.KERNEL_GRAPH,
                LifeOsStartupStage.KERNEL_BOOT,
                LifeOsStartupStage.COGNITIVE_STATE_READY,
                LifeOsStartupStage.UI_READY,
            ),
            completed.map { it.stage },
        )
        assertTrue(completed.all {
            it.evidence.manifestGraphFingerprint == LifeOsProcessTopology.manifestFingerprint
        })
        assertFalse(events.any { it.stage == LifeOsStartupStage.KERNEL_WARM_RESTORE })
    }

    @Test
    fun `warm startup isolates failure and keeps siblings completed`() = runTest {
        val actions = mutableListOf<String>()
        val events = mutableListOf<LifeOsStartupStageEvent>()
        val arrivals = AtomicInteger(0)
        val release = CompletableDeferred<Unit>()

        fun warmAction(name: String, fail: Boolean = false): suspend () -> Unit = {
            if (arrivals.incrementAndGet() == 4) release.complete(Unit)
            release.await()
            actions += name
            if (fail) error("self-healing-warm-failure")
        }

        val hooks = LifeOsStartupHooks(
            installSharedResourceRuntime = { actions += "shared-resources" },
            installGoalExecutionRuntime = { actions += "goal-execution" },
            createKernel = { actions += "kernel-created" },
            startKernel = { actions += "kernel-start" },
            requireCognitiveStateReady = { actions += "cognitive-ready" },
            warmKernelRuntime = warmAction("kernel-warm"),
            installDeepSearchRuntime = warmAction("deepsearch"),
            startSelfHealingRuntime = warmAction("self-healing", true),
            installDurableGoalPlanRuntime = warmAction("goal-plan"),
            stageObserver = { events += it },
        )

        LifeOsStartupComposition.startCritical(hooks)
        val report = LifeOsStartupComposition.startWarm(hooks)

        assertEquals(
            setOf("kernel-warm", "deepsearch", "self-healing", "goal-plan"),
            actions.takeLast(4).toSet(),
        )
        assertTrue(LifeOsStartupStage.KERNEL_WARM_RESTORE in report.completed)
        assertTrue(LifeOsStartupStage.DEEP_SEARCH in report.completed)
        assertTrue(LifeOsStartupStage.DURABLE_GOALS in report.completed)
        assertFalse(LifeOsStartupStage.RUNTIME_STARTED in report.completed)
        assertTrue(report.failures.any {
            it.stage == LifeOsStartupStage.SELF_HEALING &&
                it.message == "self-healing-warm-failure"
        })
        assertTrue(report.failures.any {
            it.stage == LifeOsStartupStage.RUNTIME_STARTED &&
                it.message.contains("dependency-failed")
        })
    }

    @Test
    fun `critical failure keeps exact diagnostic attribution`() = runTest {
        val events = mutableListOf<LifeOsStartupStageEvent>()
        val failure = runCatching {
            LifeOsStartupComposition.startCritical(
                LifeOsStartupHooks(
                    installSharedResourceRuntime = {},
                    installGoalExecutionRuntime = {},
                    createKernel = {},
                    startKernel = { error("kernel-critical-failure") },
                    requireCognitiveStateReady = {},
                    installDeepSearchRuntime = {},
                    startSelfHealingRuntime = {},
                    installDurableGoalPlanRuntime = {},
                    stageObserver = { events += it },
                )
            )
        }.exceptionOrNull()

        assertEquals("kernel-critical-failure", failure?.message)
        val failed = events.filterIsInstance<LifeOsStartupStageEvent.Failed>().single()
        assertEquals(LifeOsStartupStage.KERNEL_BOOT, failed.stage)
        assertEquals("BOOT-KB-001", failed.diagnosticCode)
    }

    @Test
    fun `startup layers expose UI boundary before parallel warm layer`() {
        assertEquals(
            listOf(
                listOf(LifeOsStartupStage.SHARED_RESOURCES),
                listOf(LifeOsStartupStage.GOAL_EXECUTION),
                listOf(LifeOsStartupStage.KERNEL_GRAPH),
                listOf(LifeOsStartupStage.KERNEL_BOOT),
                listOf(LifeOsStartupStage.COGNITIVE_STATE_READY),
                listOf(LifeOsStartupStage.UI_READY),
                listOf(
                    LifeOsStartupStage.KERNEL_WARM_RESTORE,
                    LifeOsStartupStage.DEEP_SEARCH,
                    LifeOsStartupStage.SELF_HEALING,
                    LifeOsStartupStage.DURABLE_GOALS,
                ),
                listOf(LifeOsStartupStage.RUNTIME_STARTED),
            ),
            LifeOsStartupStageGraph.layers.map { it.map(LifeOsStartupStageSpec::stage) },
        )
        assertTrue(LifeOsStartupStageGraph.layers[6].all { it.parallelSafe })
    }

    private fun hooks(
        actions: MutableList<String>,
        events: MutableList<LifeOsStartupStageEvent>,
    ) = LifeOsStartupHooks(
        installSharedResourceRuntime = { actions += "shared-resources" },
        installGoalExecutionRuntime = { actions += "goal-execution" },
        createKernel = { actions += "kernel-created" },
        startKernel = { actions += "kernel-start" },
        requireCognitiveStateReady = { actions += "cognitive-ready" },
        warmKernelRuntime = { actions += "kernel-warm" },
        installDeepSearchRuntime = { actions += "deepsearch" },
        startSelfHealingRuntime = { actions += "self-healing" },
        installDurableGoalPlanRuntime = { actions += "goal-plan" },
        stageObserver = { events += it },
    )
}
