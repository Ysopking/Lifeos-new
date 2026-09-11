package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.runtime.workers.CognitiveTaskExecutionResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DurableCognitionRecoveryObserverTest {
    @Test
    fun onlyTerminalResultsTriggerBoundedRefill() = runTest {
        var reconciliations = 0
        val observer = DurableCognitionRecoveryObserver {
            reconciliations++
        }

        observer.onExecutionResult(result(TaskState.RETRY_WAIT))
        assertEquals(0, reconciliations)

        observer.onExecutionResult(result(TaskState.COMPLETED))
        observer.onExecutionResult(result(TaskState.FAILED))
        assertEquals(2, reconciliations)
    }

    private fun result(state: TaskState) = CognitiveTaskExecutionResult(
        taskId = TaskId("task-${state.name.lowercase()}"),
        photonId = null,
        finalState = state,
        influences = emptyList(),
        failures = emptyList(),
    )
}
