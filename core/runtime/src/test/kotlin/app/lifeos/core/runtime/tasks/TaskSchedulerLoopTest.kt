package app.lifeos.core.runtime.tasks

import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskDraft
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.model.task.TaskType
import app.lifeos.core.model.worker.WorkerId
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TaskSchedulerLoopTest {
    private val t0 = Instant.parse("2026-09-07T14:00:00Z")

    @Test
    fun startImmediatelyScansPersistedRunnableTasks() = runTest {
        val repository = InMemoryTaskRepository()
        val persisted = queuedTask(repository, "persisted")
        val signal = ConflatedTaskSchedulerSignal()
        val dispatched = mutableListOf<LifeTask>()
        val loop = loop(repository, signal) { dispatched += it }

        loop.start()
        runCurrent()

        assertEquals(listOf(persisted.id), dispatched.map { it.id })
        loop.stop()
    }

    @Test
    fun durableSubmissionWakeTriggersScheduling() = runTest {
        val repository = InMemoryTaskRepository()
        val signal = ConflatedTaskSchedulerSignal()
        val dispatched = mutableListOf<LifeTask>()
        val loop = loop(repository, signal) { dispatched += it }
        val engine = DurableTaskEngine(repository, signal) { t0 }

        loop.start()
        runCurrent()
        engine.submit(
            TaskDraft(
                type = TaskType.PROCESS_PHOTON,
                idempotencyKey = "process:p1:r1",
            )
        )
        runCurrent()

        assertEquals(1, dispatched.size)
        loop.stop()
    }

    @Test
    fun periodicRescanFindsWorkEvenWithoutWakeSignal() = runTest {
        val repository = InMemoryTaskRepository()
        val signal = ConflatedTaskSchedulerSignal()
        val dispatched = mutableListOf<LifeTask>()
        val loop = loop(repository, signal) { dispatched += it }

        loop.start()
        runCurrent()
        val persisted = queuedTask(repository, "missed-wake")

        advanceTimeBy(30_000)
        runCurrent()

        assertEquals(listOf(persisted.id), dispatched.map { it.id })
        loop.stop()
    }

    @Test
    fun startIsIdempotent() = runTest {
        val repository = InMemoryTaskRepository()
        queuedTask(repository, "once")
        val signal = ConflatedTaskSchedulerSignal()
        val dispatched = mutableListOf<LifeTask>()
        val loop = loop(repository, signal) { dispatched += it }

        loop.start()
        loop.start()
        runCurrent()

        assertEquals(1, dispatched.size)
        loop.stop()
    }

    private fun loop(
        repository: InMemoryTaskRepository,
        signal: ConflatedTaskSchedulerSignal,
        dispatch: suspend (LifeTask) -> Unit,
    ): TaskSchedulerLoop {
        val scheduler = TaskScheduler(
            tasks = repository,
            workerId = WorkerId("scheduler-worker"),
            dispatcher = ClaimedTaskDispatcher(dispatch),
            now = { t0 },
        )
        return TaskSchedulerLoop(
            scope = backgroundScope,
            scheduler = scheduler,
            wakeSource = signal,
            batchSize = 16,
            rescanInterval = Duration.ofSeconds(30),
        )
    }

    private suspend fun queuedTask(
        repository: InMemoryTaskRepository,
        key: String,
    ): LifeTask {
        val task = LifeTask(
            type = TaskType.PROCESS_PHOTON,
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
