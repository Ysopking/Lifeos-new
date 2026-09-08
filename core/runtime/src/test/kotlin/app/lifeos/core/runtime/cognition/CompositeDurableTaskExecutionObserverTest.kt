package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.model.task.TaskType
import app.lifeos.core.runtime.workers.CognitiveTaskExecutionResult
import app.lifeos.core.runtime.workers.DurableTaskExecutionObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CompositeDurableTaskExecutionObserverTest {
    @Test
    fun secondaryExecutionFailureIsContainedAndLaterObserversStillRun() = runTest {
        var authoritativeCalls = 0
        var laterCalls = 0
        val composite = CompositeDurableTaskExecutionObserver(
            listOf(
                observer(onResult = { authoritativeCalls += 1 }),
                observer(onResult = { error("telemetry failed") }),
                observer(onResult = { laterCalls += 1 }),
            ),
        )

        composite.onExecutionResult(result())

        assertEquals(1, authoritativeCalls)
        assertEquals(1, laterCalls)
    }

    @Test
    fun secondaryCancellationStillPropagates() = runTest {
        var authoritativeCalls = 0
        val composite = CompositeDurableTaskExecutionObserver(
            listOf(
                observer(onResult = { authoritativeCalls += 1 }),
                observer(onResult = { throw CancellationException("cancel") }),
            ),
        )

        var thrown: Throwable? = null
        try {
            composite.onExecutionResult(result())
        } catch (cancelled: CancellationException) {
            thrown = cancelled
        }

        assertEquals(1, authoritativeCalls)
        assertIs<CancellationException>(thrown)
    }

    @Test
    fun secondaryDispatchFailureIsContainedAndLaterObserversStillRun() = runTest {
        var authoritativeCalls = 0
        var laterCalls = 0
        val composite = CompositeDurableTaskExecutionObserver(
            listOf(
                observer(onFailure = { _, _ -> authoritativeCalls += 1 }),
                observer(onFailure = { _, _ -> error("health telemetry failed") }),
                observer(onFailure = { _, _ -> laterCalls += 1 }),
            ),
        )

        composite.onDispatchFailure(task(), IllegalStateException("worker failed"))

        assertEquals(1, authoritativeCalls)
        assertEquals(1, laterCalls)
    }

    private fun observer(
        onResult: suspend (CognitiveTaskExecutionResult) -> Unit = {},
        onFailure: suspend (LifeTask, Exception) -> Unit = { _, _ -> },
    ): DurableTaskExecutionObserver = object : DurableTaskExecutionObserver {
        override suspend fun onExecutionResult(result: CognitiveTaskExecutionResult) {
            onResult(result)
        }

        override suspend fun onDispatchFailure(task: LifeTask, error: Exception) {
            onFailure(task, error)
        }
    }

    private fun result(): CognitiveTaskExecutionResult = CognitiveTaskExecutionResult(
        taskId = TaskId("task-1"),
        photonId = PhotonId("photon-1"),
        finalState = TaskState.COMPLETED,
        influences = emptyList(),
        failures = emptyList(),
    )

    private fun task(): LifeTask = LifeTask(
        id = TaskId("task-dispatch-1"),
        type = TaskType.PROCESS_PHOTON,
        inputPhotonIds = setOf(PhotonId("photon-1")),
        idempotencyKey = "dispatch-test",
    )
}
