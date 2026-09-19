package app.lifeos.core.runtime.execution

import app.lifeos.core.runtime.resource.HardwareExecutionClass
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkDecomposerGranularityTest {
    private val controller = AdaptiveGranularityController()

    @Test
    fun exactPureCacheResultWinsBeforeExecution() {
        val node = node()
        val cached = WorkResultRef.create(node.graphId, node.id, "cached-result")

        val decision = controller.decide(
            context(node, predicted = 10_000, overhead = 100, cached = cached)
        )

        assertEquals(WorkDecompositionKind.REUSE, decision.kind)
        assertEquals(cached, decision.reusedResult)
    }

    @Test
    fun splitRequiresUsefulWorkToRemainAtLeastEightTimesOverhead() {
        val node = node()

        val expensiveScheduling = controller.decide(
            context(node, predicted = 700, overhead = 100)
        )
        val economicScheduling = controller.decide(
            context(node, predicted = 900, overhead = 100)
        )

        assertEquals(WorkDecompositionKind.BATCH, expensiveScheduling.kind)
        assertEquals(WorkDecompositionKind.SPLIT, economicScheduling.kind)
    }

    @Test
    fun smallWorkStaysAtomic() {
        val decision = controller.decide(
            context(node(), predicted = 64, overhead = 4)
        )

        assertEquals(WorkDecompositionKind.ATOMIC, decision.kind)
    }

    private fun context(
        node: WorkNode,
        predicted: Long,
        overhead: Long,
        cached: WorkResultRef? = null,
    ) = WorkDecompositionContext(
        node = node,
        predictedWorkUnits = predicted,
        schedulingOverheadWorkUnits = overhead,
        adaptiveQuantum = 128,
        maximumBatchSize = 16,
        availableParallelism = 4,
        cachedExactResult = cached,
    )

    private fun node(): WorkNode {
        val graphId = WorkGraphId.create(
            "decomposer",
            "input",
            "strategy",
            "equation",
            "modules",
        )
        return WorkNode.create(
            graphId = graphId,
            operationKind = "TEST",
            executorId = "executor",
            executorImplementationFingerprint = "executor-v1",
            inputs = listOf(WorkInputRef("source", 1, "source-fingerprint")),
            estimatedCost = WorkCostEstimate(1_000, 100),
            executionClass = HardwareExecutionClass.CPU_COMPUTE,
        )
    }
}
