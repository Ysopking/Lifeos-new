package app.lifeos.next

import app.lifeos.core.runtime.topology.LifeOsProcessTopology
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest

class LifeOsStartupCompositionTest {
    @Test
    fun `startup DAG preserves prerequisites and deterministic completion evidence`() = runTest {
        val actions = mutableListOf<String>()
        val evidence = mutableListOf<LifeOsStartupStageEvidence>()
        val arrivals = AtomicInteger(0)
        val parallelRelease = CompletableDeferred<Unit>()

        fun parallelAction(name: String): suspend () -> Unit = {
            if (arrivals.incrementAndGet() == 3) {
                parallelRelease.complete(Unit)
            }
            parallelRelease.await()
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
        assertTrue(
            evidence.all {
                it.manifestGraphFingerprint == LifeOsProcessTopology.manifestFingerprint
            }
        )
    }

    @Test
    fun `parallel startup failure cancels siblings and blocks later evidence`() = runTest {
        val evidence = mutableListOf<LifeOsStartupStageEvidence>()
        val siblingStarts = AtomicInteger(0)
        val siblingsReady = CompletableDeferred<Unit>()
        val cancelledSiblings = mutableListOf<String>()

        fun cancellableSibling(name: String): suspend () -> Unit = {
            if (siblingStarts.incrementAndGet() == 2) {
                siblingsReady.complete(Unit)
            }
            try {
                awaitCancellation()
            } finally {
                cancelledSiblings += name
            }
        }

        val failure = try {
            LifeOsStartupComposition.start(
                LifeOsStartupHooks(
                    installSharedResourceRuntime = {},
                    installGoalExecutionRuntime = {},
                    createKernel = {},
                    startKernel = {},
                    requireCognitiveStateReady = {},
                    installDeepSearchRuntime = cancellableSibling("deepsearch"),
                    startSelfHealingRuntime = {
                        siblingsReady.await()
                        error("startup-failure")
                    },
                    installDurableGoalPlanRuntime = cancellableSibling("goal-plan"),
                    stageObserver = { evidence += it },
                )
            )
            null
        } catch (error: IllegalStateException) {
            error
        }

        assertEquals("startup-failure", failure?.message)
        assertEquals(
            setOf("deepsearch", "goal-plan"),
            cancelledSiblings.toSet(),
        )
        assertFalse(
            evidence.any { it.stage == LifeOsStartupStage.RUNTIME_STARTED }
        )
        assertEquals(
            LifeOsStartupStage.COGNITIVE_STATE_READY,
            evidence.last().stage,
        )
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
