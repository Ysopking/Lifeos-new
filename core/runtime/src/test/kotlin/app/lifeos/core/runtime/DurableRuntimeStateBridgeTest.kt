package app.lifeos.core.runtime

import app.lifeos.core.model.FieldInfluence
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.runtime.workers.CognitiveTaskExecutionResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DurableRuntimeStateBridgeTest {
    @Test
    fun completedExecutionUpdatesProcessedPhotonAndInfluences() = runTest {
        val bridge = DurableRuntimeStateBridge()
        val photonId = PhotonId.new()
        bridge.markRunning()

        bridge.onExecutionResult(
            CognitiveTaskExecutionResult(
                taskId = TaskId.new(),
                photonId = photonId,
                finalState = TaskState.COMPLETED,
                influences = listOf(
                    FieldInfluence(
                        module = "matrix",
                        photonId = photonId,
                        type = "INDEX",
                        deltaEnergy = 0.1,
                        confidence = 1.0,
                        explanation = "indexed",
                    )
                ),
                failures = emptyList(),
            )
        )

        val state = bridge.state.value
        assertEquals(RuntimeStatus.RUNNING, state.status)
        assertEquals(1, state.processed)
        assertEquals(0, state.failed)
        assertEquals(photonId, state.lastPhotonId)
        assertEquals(1, state.recentInfluences.size)
    }

    @Test
    fun supersededExecutionIsNeutralForProcessedAndFailedCounters() = runTest {
        val bridge = DurableRuntimeStateBridge()
        val photonId = PhotonId.new()
        bridge.markRunning()

        bridge.onExecutionResult(
            CognitiveTaskExecutionResult(
                taskId = TaskId.new(),
                photonId = photonId,
                finalState = TaskState.SUPERSEDED,
                influences = emptyList(),
                failures = emptyList(),
            )
        )

        val state = bridge.state.value
        assertEquals(RuntimeStatus.RUNNING, state.status)
        assertEquals(0, state.processed)
        assertEquals(0, state.failed)
        assertEquals(photonId, state.lastPhotonId)
    }

    @Test
    fun failedExecutionUpdatesFailureWithoutIncrementingProcessed() = runTest {
        val bridge = DurableRuntimeStateBridge()
        val photonId = PhotonId.new()
        val failure = RuntimeFailure(
            category = RuntimeFailureCategory.FIELD,
            source = "field[0]",
            message = "broken",
            photonId = photonId,
        )

        bridge.onExecutionResult(
            CognitiveTaskExecutionResult(
                taskId = TaskId.new(),
                photonId = photonId,
                finalState = TaskState.FAILED,
                influences = emptyList(),
                failures = listOf(failure),
            )
        )

        val state = bridge.state.value
        assertEquals(0, state.processed)
        assertEquals(1, state.failed)
        assertEquals(failure, state.lastFailure)
        assertEquals(photonId, state.lastPhotonId)
    }
}
