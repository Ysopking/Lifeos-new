package app.lifeos.core.runtime.workers

import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CompositeDurableTaskExecutionObserverTest {
    @Test
    fun secondaryObserverFailureDoesNotChangePrimaryResultFlow() = runTest {
        var primaryResults = 0
        var secondaryResults = 0
        val primary = object : DurableTaskExecutionObserver {
            override suspend fun onExecutionResult(result: CognitiveTaskExecutionResult) {
                primaryResults++
            }
        }
        val secondary = object : DurableTaskExecutionObserver {
            override suspend fun onExecutionResult(result: CognitiveTaskExecutionResult) {
                secondaryResults++
                error("telemetry failed")
            }
        }
        val composite = CompositeDurableTaskExecutionObserver(primary, listOf(secondary))

        composite.onExecutionResult(
            CognitiveTaskExecutionResult(
                taskId = TaskId("task-1"),
                photonId = null,
                finalState = TaskState.COMPLETED,
                influences = emptyList(),
                failures = emptyList(),
            )
        )

        assertEquals(1, primaryResults)
        assertEquals(1, secondaryResults)
    }

    @Test
    fun secondaryDispatchFailureObserverCannotMaskOriginalFailureReporting() = runTest {
        var primaryFailures = 0
        val primary = object : DurableTaskExecutionObserver {
            override suspend fun onExecutionResult(result: CognitiveTaskExecutionResult) = Unit

            override suspend fun onDispatchFailure(task: LifeTask, error: Exception) {
                primaryFailures++
            }
        }
        val secondary = object : DurableTaskExecutionObserver {
            override suspend fun onExecutionResult(result: CognitiveTaskExecutionResult) = Unit

            override suspend fun onDispatchFailure(task: LifeTask, error: Exception) {
                error("health observer unavailable")
            }
        }
        val composite = CompositeDurableTaskExecutionObserver(primary, listOf(secondary))
        val task = LifeTask(id = TaskId("task-2"))

        composite.onDispatchFailure(task, IllegalStateException("dispatch failed"))

        assertEquals(1, primaryFailures)
    }
}
