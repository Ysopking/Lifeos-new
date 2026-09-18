package app.lifeos.core.runtime.tasks

import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskPriority
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.model.task.TaskType
import app.lifeos.core.model.worker.WorkerId
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PooledTaskSchedulerTest {
    private val t0 = Instant.parse("2026-09-18T12:00:00Z")

    @Test
    fun blockedBackgroundWorkerDoesNotBlockLaterInteractiveDispatch() = runTest {
        val repository = InMemoryTaskRepository()
        val backgroundGate = CompletableDeferred<Unit>()
        val backgroundStarted = CompletableDeferred<Unit>()
        val interactiveDispatched = mutableListOf<LifeTask>()
        val signal = ConflatedTaskSchedulerSignal()

        val pool = CognitiveWorkerPool(
            listOf(
                CognitiveWorkerSlot(
                    lane = CognitiveWorkerLane.INTERACTIVE,
                    workerId = WorkerId("interactive-0"),
                    dispatcher = ClaimedTaskDispatcher { task ->
                        interactiveDispatched += task
                    },
                ),
                CognitiveWorkerSlot(
                    lane = CognitiveWorkerLane.BACKGROUND,
                    workerId = WorkerId("background-0"),
                    dispatcher = ClaimedTaskDispatcher {
                        backgroundStarted.complete(Unit)
                        backgroundGate.await()
                    },
                ),
            )
        )
        val scheduler = PooledTaskScheduler(
            tasks = repository,
            workers = pool,
            scope = backgroundScope,
            workerAvailableSignal = signal,
            now = { t0 },
        )

        queuedTask(repository, "background", TaskPriority.BACKGROUND)
        val first = scheduler.scheduleOnce()
        assertEquals(1, first.claimed)
        assertEquals(1, first.dispatched)

        runCurrent()
        assertTrue(backgroundStarted.isCompleted)
        assertEquals(setOf(WorkerId("background-0")), scheduler.busyWorkerIds())

        val interactive = queuedTask(repository, "interactive", TaskPriority.INTERACTIVE)
        val second = scheduler.scheduleOnce()

        assertEquals(1, second.claimed)
        assertEquals(1, second.dispatched)
        runCurrent()
        assertEquals(listOf(interactive.id), interactiveDispatched.map { it.id })
        assertTrue(WorkerId("background-0") in scheduler.busyWorkerIds())

        backgroundGate.complete(Unit)
        runCurrent()
        assertTrue(scheduler.busyWorkerIds().isEmpty())
    }

    @Test
    fun backgroundPriorityNeverConsumesReservedInteractiveSlot() = runTest {
        val repository = InMemoryTaskRepository()
        val pool = CognitiveWorkerPool(
            listOf(
                CognitiveWorkerSlot(
                    lane = CognitiveWorkerLane.INTERACTIVE,
                    workerId = WorkerId("interactive-0"),
                    dispatcher = ClaimedTaskDispatcher { },
                )
            )
        )
        val scheduler = PooledTaskScheduler(
            tasks = repository,
            workers = pool,
            scope = backgroundScope,
            now = { t0 },
        )
        queuedTask(repository, "background-only", TaskPriority.BACKGROUND)

        val result = scheduler.scheduleOnce()

        assertEquals(0, result.claimed)
        assertEquals(0, result.dispatched)
        assertTrue(scheduler.busyWorkerIds().isEmpty())
    }

    private suspend fun queuedTask(
        repository: InMemoryTaskRepository,
        key: String,
        priority: TaskPriority,
    ): LifeTask {
        val task = LifeTask(
            type = TaskType.PROCESS_PHOTON,
            priority = priority,
            idempotencyKey = key,
            createdAt = t0,
            updatedAt = t0,
        )
        repository.create(task)
        return checkNotNull(
            repository.transition(
                task.id,
                TaskState.CREATED,
                TaskState.QUEUED,
                t0,
            )
        )
    }
}
