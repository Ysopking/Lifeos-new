package app.lifeos.core.runtime.execution

import app.lifeos.core.model.task.TaskPriority
import app.lifeos.core.runtime.resource.HardwareExecutionClass
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkGraphDeterminismTest {
    @Test
    fun replayBuildsIdenticalGraphAndNodeIds() {
        val first = graph()
        val second = graph()

        assertEquals(first.id, second.id)
        assertEquals(first.nodes.map { it.id }, second.nodes.map { it.id })
        assertEquals(first.contentFingerprint(), second.contentFingerprint())
    }

    @Test
    fun dependencyCycleIsRejected() {
        val graphId = graphId()
        val input = listOf(WorkInputRef("photon:p1", 1, "input-fingerprint"))
        val aSeed = WorkNode.create(
            graphId = graphId,
            operationKind = "A",
            executorId = "exec-a",
            executorImplementationFingerprint = "exec-a-v1",
            inputs = input,
            estimatedCost = WorkCostEstimate(10),
            executionClass = HardwareExecutionClass.CPU_LIGHT,
        )
        val b = WorkNode.create(
            graphId = graphId,
            parentNodeId = aSeed.id,
            dependencies = setOf(aSeed.id),
            operationKind = "B",
            executorId = "exec-b",
            executorImplementationFingerprint = "exec-b-v1",
            inputs = input,
            estimatedCost = WorkCostEstimate(10),
            executionClass = HardwareExecutionClass.CPU_LIGHT,
        )
        val a = aSeed.copy(dependencies = setOf(b.id))

        assertFailsWith<IllegalArgumentException> {
            WorkGraph(
                id = graphId,
                revision = 1,
                state = WorkGraphState.ACTIVE,
                nodes = listOf(a, b),
                terminalNodeId = b.id,
                createdAt = NOW,
                updatedAt = NOW,
            )
        }
    }

    private fun graph(): WorkGraph {
        val id = graphId()
        val input = listOf(WorkInputRef("photon:p1", 7, "photon-fingerprint"))
        val first = WorkNode.create(
            graphId = id,
            operationKind = "FIELD_A",
            executorId = "field-executor",
            executorImplementationFingerprint = "field-executor-v1",
            inputs = input,
            estimatedCost = WorkCostEstimate(workUnits = 50, schedulingOverheadUnits = 2),
            executionClass = HardwareExecutionClass.CPU_COMPUTE,
            priority = TaskPriority.NORMAL,
            partition = 0,
        )
        val second = WorkNode.create(
            graphId = id,
            parentNodeId = first.id,
            dependencies = setOf(first.id),
            operationKind = "REDUCE",
            executorId = "field-reducer",
            executorImplementationFingerprint = "field-reducer-v1",
            inputs = input,
            estimatedCost = WorkCostEstimate(workUnits = 10),
            executionClass = HardwareExecutionClass.CPU_LIGHT,
            priority = TaskPriority.NORMAL,
            partition = 0,
        )
        return WorkGraph(
            id = id,
            revision = 1,
            state = WorkGraphState.ACTIVE,
            nodes = listOf(first, second),
            terminalNodeId = second.id,
            createdAt = NOW,
            updatedAt = NOW,
        )
    }

    private fun graphId() = WorkGraphId.create(
        namespace = "test",
        semanticInputFingerprint = "input-root",
        strategyFingerprint = "strategy-v1",
        equationVersion = "equation-v1",
        moduleSnapshotFingerprint = "modules-v1",
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-19T03:30:00Z")
    }
}
