package app.lifeos.core.runtime.recovery

import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.model.task.TaskType
import app.lifeos.core.model.worker.WorkerId
import app.lifeos.core.runtime.tasks.InMemoryTaskRepository
import app.lifeos.core.runtime.tasks.TaskSchedulerSignal
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LeaseRecoveryServiceTest {
    private val now = Instant.parse("2026-09-07T18:00:00Z")

    @Test
    fun expiredRunningTaskIsRequeuedAndSchedulerIsWoken() = runTest {
        val repository = InMemoryTaskRepository()
        val task = expiredRunningTask(repository, "expired")
        val signal = RecordingSignal()
        val service = LeaseRecoveryService(repository, signal) { now }

        val result = service.recoverExpired()

        assertEquals(1, result.scanned)
        assertEquals(1, result.recovered)
        assertEquals(0, result.skipped)
        assertEquals(task.id, result.recoveredTasks.single().id)
        assertEquals(TaskState.QUEUED, repository.get(task.id)?.state)
        assertEquals(null, repository.get(task.id)?.claimedBy)
        assertEquals(null, repository.get(task.id)?.leaseExpiresAt)
        assertEquals(1, signal.wakes)
    }

    @Test
    fun currentLeaseIsLeftUntouched() = runTest {
        val repository = InMemoryTaskRepository()
        val task = queuedTask(repository, "current")
        val claimed = checkNotNull(
            repository.claim(
                task.id,
                WorkerId("worker"),
                now.minusSeconds(5),
                now.plusSeconds(30),
            )
        )
        val signal = RecordingSignal()
        val service = LeaseRecoveryService(repository, signal) { now }

        val result = service.recoverExpired()

        assertEquals(0, result.scanned)
        assertEquals(TaskState.CLAIMED, repository.get(claimed.id)?.state)
        assertEquals(0, signal.wakes)
    }

    @Test
    fun completedTaskIsNeverRecovered() = runTest {
        val repository = InMemoryTaskRepository()
        val running = expiredRunningTask(repository, "completed")
        val completed = checkNotNull(
            repository.transition(
                running.id,
                TaskState.RUNNING,
                TaskState.COMPLETED,
                now.minusSeconds(1),
            )
        )
        val signal = RecordingSignal()
        val service = LeaseRecoveryService(repository, signal) { now }

        val result = service.recoverExpired()

        assertEquals(0, result.scanned)
        assertEquals(TaskState.COMPLETED, repository.get(completed.id)?.state)
        assertEquals(0, signal.wakes)
    }

    private suspend fun expiredRunningTask(
        repository: InMemoryTaskRepository,
        key: String,
    ): LifeTask {
        val queued = queuedTask(repository, key)
        val claimed = checkNotNull(
            repository.claim(
                queued.id,
                WorkerId("old-worker"),
                now.minusSeconds(60),
                now.minusSeconds(30),
            )
        )
        return checkNotNull(
            repository.transition(
                claimed.id,
                TaskState.CLAIMED,
                TaskState.RUNNING,
                now.minusSeconds(50),
            )
        )
    }

    private suspend fun queuedTask(
        repository: InMemoryTaskRepository,
        key: String,
    ): LifeTask {
        val createdAt = now.minusSeconds(120)
        val task = LifeTask(
            type = TaskType.PROCESS_PHOTON,
            idempotencyKey = key,
            createdAt = createdAt,
            updatedAt = createdAt,
        )
        repository.create(task)
        return checkNotNull(
            repository.transition(
                task.id,
                TaskState.CREATED,
                TaskState.QUEUED,
                createdAt,
            )
        )
    }

    private class RecordingSignal : TaskSchedulerSignal {
        var wakes = 0
            private set

        override suspend fun wake() {
            wakes += 1
        }
    }
}
