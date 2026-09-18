package app.lifeos.next

import app.lifeos.core.runtime.topology.LifeOsProcessTopology
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LifeOsStartupCompositionTest {
    @Test
    fun `startup DAG preserves prerequisites and deterministic completion evidence`() {
        val actions = Collections.synchronizedList(mutableListOf<String>())
        val evidence = mutableListOf<LifeOsStartupStageEvidence>()
        val parallelOverlap = CountDownLatch(2)

        fun parallelAction(name: String): suspend () -> Unit = {
            parallelOverlap.countDown()
            check(parallelOverlap.await(2, TimeUnit.SECONDS)) {
                "Independent post-kernel startup stages did not overlap"
            }
            actions += name
        }

        LifeOsStartupComposition.start(
            LifeOsStartupHooks(
                installSharedResourceRuntime = { actions += "shared-resources" },
                installGoalExecutionRuntime = { actions += "goal-execution" },
                createKernel = { actions += "kernel-created" },
                startKernel = { actions += "kernel-start" },
                requireCognitiveStateReady = { actions += "cognitive-ready" },
                installDeepSearchRuntime = parallelAction("deepsearch"),
                startSelfHealingRuntime = parallelAction("self-healing"),
                installDurableGoalPlanRuntime = parallelAction("goal-plan"),
                stageObserver = { evidence += it },
            )
        )

        assertEquals(
            listOf("shared-resources", "goal-execution", "kernel-created"),
            actions.take(3),
        )
        assertEquals(
            listOf("kernel-start", "cognitive-ready"),
            actions.subList(3, 5),
        )
        assertEquals(
            listOf("deepsearch", "self-healing", "goal-plan").toSet(),
            actions.subList(5, 8).toSet(),
        )
        assertEquals(
            LifeOsStartupStage.entries.toList(),
            evidence.map { it.stage },
        )
        assertTrue(evidence.all { it.manifestGraphFingerprint == LifeOsProcessTopology.manifestFingerprint })
    }

    @Test
    fun `startup layers are deterministic and expose one parallel post-kernel layer`() {
        assertEquals(
            listOf(
                listOf(LifeOsStartupStage.SHARED_RESOURCES),
                listOf(LifeOsStartupStage.GOAL_EXECUTION),
                listOf(LifeOsStartupStage.KERNEL_GRAPH),
                listOf(LifeOsStartupStage.KERNEL_BOOT),
                listOf(LifeOsStartupStage.COGNITIVE_STATE_READY),
                listOf(
                    LifeOsStartupStage.DEEP_SEARCH,
                    LifeOsStartupStage.SELF_HEALING,
                    LifeOsStartupStage.DURABLE_GOALS,
                ),
                listOf(LifeOsStartupStage.RUNTIME_STARTED),
            ),
            LifeOsStartupStageGraph.layers.map { layer -> layer.map { it.stage } },
        )
        assertTrue(LifeOsStartupStageGraph.layers[5].all { it.parallelSafe })
    }
}
