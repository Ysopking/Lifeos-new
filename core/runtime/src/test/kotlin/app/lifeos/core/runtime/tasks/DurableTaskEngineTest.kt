package app.lifeos.core.runtime.tasks

import app.lifeos.core.model.task.CreateTaskResult
import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskDraft
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskRepository
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.model.task.TaskType
import app.lifeos.core.model.worker.WorkerId
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DurableTaskEngineTest {
    private val t0 = Instant.parse("2026-09-07T12:00:00Z")

    @Test
    fun submitPersistsQueuesAndWakesScheduler() = runTest {
        val repository = InMemoryTaskRepository()
        val signal = RecordingSignal()
        val engine = DurableTaskEngine(repository, signal) { t0 }

        val queued = engine.submit(
            TaskDraft(
                type = TaskType.PROCESS_PHOTON,
                idempotencyKey = "process:p1:r1",
            )
        )

        assertEquals(TaskState.QUEUED, queued.state)
        assertEquals(queued, repository.get(queued.id))
        assertEquals(1, signal.wakes)
    }

    @Test
    fun duplicateSubmitReturnsSameLogicalTask() = runTest {
        val repository = InMemoryTaskRepository()
        val signal = RecordingSignal()
        val engine = DurableTaskEngine(repository, signal) { t0 }
        val draft = TaskDraft(
            type = TaskType.PROCESS_PHOTON,
            idempotencyKey = "process:p1:r1",
        )

        val first = engine.submit(draft)
        val second = engine.submit(draft)

        assertEquals(first.id, second.id)
        assertEquals(TaskState.QUEUED, second.state)
        assertEquals(2, signal.wakes)
    }

    @Test
    fun duplicateSubmitRepairsPersistedCreatedTask() = runTest {
        val repository = InMemoryTaskRepository()
        val signal = RecordingSignal()
        val existing = LifeTask(
            type = TaskType.PROCESS_PHOTON,
            idempotencyKey = "process:p1:r1",
            createdAt = t0,
            updatedAt = t0,
        )
        repository.create(existing)

        val engine = DurableTaskEngine(repository, signal) { t0 }
        val resumed = engine.submit(
            TaskDraft(
                type = TaskType.PROCESS_PHOTON,
                idempotencyKey = existing.idempotencyKey,
            )
        )

        assertEquals(existing.id, resumed.id)
        assertEquals(TaskState.QUEUED, resumed.state)
        assertEquals(1, signal.wakes)
    }

    @Test
    fun concurrentQueueWinnerKeepsDuplicateSubmissionIdempotent() = runTest {
        val repository = LostQueueRaceRepository()
        val signal = RecordingSignal()
        val engine = DurableTaskEngine(repository, signal) { t0 }

        val queued = engine.submit(
            TaskDraft(
                type = TaskType.PROCESS_PHOTON,
                idempotencyKey = "process:race:r1",
            )
        )

        assertEquals(TaskState.QUEUED, queued.state)
        assertEquals(queued, repository.get(queued.id))
        assertEquals(1, signal.wakes)
    }

    /**
     * Simulates the exact create/queue race: another duplicate submit wins CREATED -> QUEUED after
     * create() but before this submit's CAS result is observed, so transition() returns null while
     * the authoritative repository state is already QUEUED.
     */
    private class LostQueueRaceRepository : TaskRepository {
        private var current: LifeTask? = null
        private var queueRaceInjected = false

        override suspend fun create(task: LifeTask): CreateTaskResult {
            val existing = current
            if (existing != null) return CreateTaskResult.Existing(existing)
            current = task
            return CreateTaskResult.Created(task)
        }

        override suspend fun get(id: TaskId): LifeTask? =
            current?.takeIf { it.id == id }

        override suspend fun findByIdempotencyKey(key: String): LifeTask? =
            current?.takeIf { it.idempotencyKey == key }

        override suspend fun listRunnable(now: Instant, limit: Int): List<LifeTask> =
            current?.takeIf { it.state == TaskState.QUEUED }?.let(::listOf).orEmpty()

        override suspend fun listExpiredLeases(now: Instant, limit: Int): List<LifeTask> =
            emptyList()

        override suspend fun transition(
            id: TaskId,
            expected: TaskState,
            next: TaskState,
            at: Instant,
        ): LifeTask? {
            val task = current ?: return null
            if (task.id != id || task.state != expected) return null
            val updated = task.copy(
                state = next,
                updatedAt = at,
                claimedBy = null,
                leaseExpiresAt = null,
            )
            current = updated
            if (
                !queueRaceInjected &&
                expected == TaskState.CREATED &&
                next == TaskState.QUEUED
            ) {
                queueRaceInjected = true
                return null
            }
            return updated
        }

        override suspend fun claim(
            id: TaskId,
            workerId: WorkerId,
            acquiredAt: Instant,
            leaseUntil: Instant,
        ): LifeTask? = error("unused")

        override suspend fun startExecution(
            id: TaskId,
            workerId: WorkerId,
            startedAt: Instant,
        ): LifeTask? = error("unused")

        override suspend fun finishExecution(
            id: TaskId,
            workerId: WorkerId,
            finalState: TaskState,
            finishedAt: Instant,
        ): LifeTask? = error("unused")

        override suspend fun interruptExecution(
            id: TaskId,
            workerId: WorkerId,
            interruptedAt: Instant,
        ): LifeTask? = error("unused")

        override suspend fun scheduleRetry(
            id: TaskId,
            workerId: WorkerId,
            retryAt: Instant,
            scheduledAt: Instant,
        ): LifeTask? = error("unused")

        override suspend fun renewLease(
            id: TaskId,
            workerId: WorkerId,
            renewedAt: Instant,
            leaseUntil: Instant,
        ): LifeTask? = error("unused")

        override suspend fun interruptExpiredLease(
            id: TaskId,
            expectedState: TaskState,
            expectedWorkerId: WorkerId,
            expectedLeaseExpiresAt: Instant,
            at: Instant,
        ): LifeTask? = error("unused")
    }

    private class RecordingSignal : TaskSchedulerSignal {
        var wakes = 0
            private set

        override suspend fun wake() {
            wakes += 1
        }
    }
}
