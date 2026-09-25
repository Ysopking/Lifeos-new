package app.lifeos.next

import app.lifeos.core.runtime.topology.LifeOsProcessTopology
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class LifeOsStartupCompositionTest {
    @Test
    fun `critical startup reaches runtime boundary before warm stages`() = runTest {
        val actions = mutableListOf<String>()
        val events = mutableListOf<LifeOsStartupStageEvent>()
        val hooks = LifeOsStartupHooks(
            installSharedResourceRuntime = { actions += "shared-resources" },
            installGoalExecutionRuntime = { actions += "goal-execution" },
            createKernel = { actions += "kernel-created" },
            startKernel = { actions += "kernel-start" },
            requireCognitiveStateReady = { actions += "cognitive-ready" },
            warmPersonalRuntime = { actions += "personal-warmup" },
            installDeepSearchRuntime = { actions += "deepsearch" },
            startSelfHealingRuntime = { actions += "self-healing" },
            installDurableGoalPlanRuntime = { actions += "goal-plan" },
            stageObserver = { events += it },
        )

        LifeOsStartupComposition.startCritical(hooks)

        assertEquals(
            listOf(
                "shared-resources",
                "goal-execution",
                "kernel-created",
                "kernel-start",
                "cognitive-ready",
            ),
            actions,
        )
        val criticalCompleted =
            events.filterIsInstance<LifeOsStartupStageEvent.Completed>().map { it.stage }
        assertEquals(
            listOf(
                LifeOsStartupStage.SHARED_RESOURCES,
                LifeOsStartupStage.GOAL_EXECUTION,
                LifeOsStartupStage.KERNEL_GRAPH,
                LifeOsStartupStage.KERNEL_BOOT,
                LifeOsStartupStage.COGNITIVE_STATE_READY,
                LifeOsStartupStage.RUNTIME_STARTED,
            ),
            criticalCompleted,
        )
        assertFalse(events.any { LifeOsStartupStageGraph.laneFor(it.stage) == LifeOsStartupLane.WARM })

        val report = LifeOsStartupComposition.startWarm(hooks)

        assertFalse(report.degraded)
        assertEquals(
            setOf(
                LifeOsStartupStage.PERSONAL_RUNTIME_WARMUP,
                LifeOsStartupStage.DEEP_SEARCH,
                LifeOsStartupStage.SELF_HEALING,
                LifeOsStartupStage.DURABLE_GOALS,
            ),
            report.completedStages,
        )
        assertEquals(
            setOf("personal-warmup", "deepsearch", "self-healing", "goal-plan"),
            actions.drop(5).toSet(),
        )
        assertTrue(
            events.filterIsInstance<LifeOsStartupStageEvent.Completed>().all {
                it.evidence.manifestGraphFingerprint == LifeOsProcessTopology.manifestFingerprint
            }
        )
    }

    @Test
    fun `warm startup failure does not cancel successful siblings`() = runTest {
        val actions = mutableListOf<String>()
        val events = mutableListOf<LifeOsStartupStageEvent>()
        val hooks = LifeOsStartupHooks(
            installSharedResourceRuntime = {},
            installGoalExecutionRuntime = {},
            createKernel = {},
            startKernel = {},
            requireCognitiveStateReady = {},
            warmPersonalRuntime = { actions += "personal-warmup" },
            installDeepSearchRuntime = { actions += "deepsearch" },
            startSelfHealingRuntime = {
                actions += "self-healing"
                error("startup-failure")
            },
            installDurableGoalPlanRuntime = { actions += "goal-plan" },
            stageObserver = { events += it },
        )

        LifeOsStartupComposition.startCritical(hooks)
        val report = LifeOsStartupComposition.startWarm(hooks)

        assertEquals(
            setOf("personal-warmup", "deepsearch", "self-healing", "goal-plan"),
            actions.toSet(),
        )
        assertTrue(report.degraded)
        assertEquals(
            setOf(
                LifeOsStartupStage.PERSONAL_RUNTIME_WARMUP,
                LifeOsStartupStage.DEEP_SEARCH,
                LifeOsStartupStage.DURABLE_GOALS,
            ),
            report.completedStages,
        )
        val failure = report.failures.single()
        assertEquals(LifeOsStartupStage.SELF_HEALING, failure.stage)
        assertEquals("BOOT-SH-001", failure.diagnosticCode)
        assertEquals("startup-failure", failure.message)

        val failedEvent = events.filterIsInstance<LifeOsStartupStageEvent.Failed>().single()
        assertEquals(LifeOsStartupStage.SELF_HEALING, failedEvent.stage)
        assertTrue(
            events.filterIsInstance<LifeOsStartupStageEvent.Completed>()
                .any { it.stage == LifeOsStartupStage.RUNTIME_STARTED }
        )
    }

    @Test
    fun `startup stage diagnostic identities are stable and unique`() {
        assertEquals(
            LifeOsStartupStage.entries.size,
            LifeOsStartupStage.entries.map { it.diagnosticCode }.distinct().size,
        )
        assertEquals("BOOT-CS-003", LifeOsStartupStage.COGNITIVE_STATE_READY.diagnosticCode)
        assertTrue(LifeOsStartupStage.entries.all { it.displayName.isNotBlank() })
    }

    @Test
    fun `startup lanes are deterministic and warm depends on runtime boundary`() {
        assertEquals(
            listOf(
                listOf(LifeOsStartupStage.SHARED_RESOURCES),
                listOf(LifeOsStartupStage.GOAL_EXECUTION),
                listOf(LifeOsStartupStage.KERNEL_GRAPH),
                listOf(LifeOsStartupStage.KERNEL_BOOT),
                listOf(LifeOsStartupStage.COGNITIVE_STATE_READY),
                listOf(LifeOsStartupStage.RUNTIME_STARTED),
                listOf(
                    LifeOsStartupStage.DEEP_SEARCH,
                    LifeOsStartupStage.SELF_HEALING,
                    LifeOsStartupStage.DURABLE_GOALS,
                    LifeOsStartupStage.PERSONAL_RUNTIME_WARMUP,
                ),
            ),
            LifeOsStartupStageGraph.layers.map { layer -> layer.map { it.stage } },
        )
        assertTrue(
            LifeOsStartupStageGraph.specsFor(LifeOsStartupLane.CRITICAL)
                .all { it.stage !in setOf(
                    LifeOsStartupStage.PERSONAL_RUNTIME_WARMUP,
                    LifeOsStartupStage.DEEP_SEARCH,
                    LifeOsStartupStage.SELF_HEALING,
                    LifeOsStartupStage.DURABLE_GOALS,
                ) }
        )
        assertTrue(
            LifeOsStartupStageGraph.specsFor(LifeOsStartupLane.WARM)
                .all { LifeOsStartupStage.RUNTIME_STARTED in it.dependencies }
        )
    }
}
