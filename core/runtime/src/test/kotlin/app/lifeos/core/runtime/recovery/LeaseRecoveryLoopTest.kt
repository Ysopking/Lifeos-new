package app.lifeos.core.runtime.recovery

import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.model.task.TaskType
import app.lifeos.core.model.worker.WorkerId
import app.lifeos.core.runtime.tasks.InMemoryTaskRepository
import app.lifeos.core.runtime.tasks.TaskSchedulerSignal
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LeaseRecoveryLoopTest {
    private val t0 = Instant.parse("2026-09-07T18:00:00Z")

    @Test
    fun startImmediatelyRecoversAlreadyExpiredLease() = runTest {
        var now = t0
        val repository = InMemoryTaskRepository()
        val running = runningTask(
            repository = repository,
            key = "expired-at-start",
            acquiredAt = t0.minusSeconds(60),
            leaseUntil = t0.minusSeconds(30),
        )
        val signal = RecordingSignal()
        val service = LeaseRecoveryService(repository, signal) { now }
        val loop = LeaseRecoveryLoop(
            scope = backgroundScope,
            recovery = service,
            interval = Duration.ofSeconds(30),
        )

        loop.start()
        runCurrent()

        assertEquals(TaskState.QUEUED, repository.get(running.id)?.state)
        assertEquals(1, signal.wakes)
        loop.stop()
    }

    @Test
    fun leaseThatExpiresAfterRestartIsRecoveredOnLaterCycle() = runTest {
        var now = t0
        val repository = InMemoryTaskRepository()
        val running = runningTask(
            repository = repository,
            key = "expires-later",
            acquiredAt = t0.minusSeconds(5),
            leaseUntil = t0.plusSeconds(20),
        )
        val signal = RecordingSignal()
        val service = LeaseRecoveryService(repository, signal) { now }
        val loop = LeaseRecoveryLoop(
            scope = backgroundScope,
            recovery = service,
            interval = Duration.ofSeconds(30),
        )

        loop.start()
        runCurrent()
        assertEquals(TaskState.RUNNING, repository.get(running.id)?.state)
        assertEquals(0, signal.wakes)

        now = t0.plusSeconds(31)
        advanceTimeBy(30_000)
        runCurrent()

        assertEquals(TaskState.QUEUED, repository.get(running.id)?.state)
        assertEquals(1, signal.wakes)
        loop.stop()
    }

    @Test
    fun startIsIdempotent() = runTest {
        var now = t0
        val repository = InMemoryTaskRepository()
        val running = runningTask(
            repository = repository,
            key = "idempotent",
            acquiredAt = t0.minusSeconds(60),
            leaseUntil = t0.minusSeconds(30),
        )
        val signal = RecordingSignal()
        val service = LeaseRecoveryService(repository, signal) { now }
        val loop = LeaseRecoveryLoop(
            scope = backgroundScope,
            recovery = service,
            interval = Duration.ofSeconds(30),
        )

        loop.start()
        loop.start()
        runCurrent()

        assertEquals(TaskState.QUEUED, repository.get(running.id)?.state)
        assertEquals(1, signal.wakes)
        loop.stop()
    }

    private suspend fun runningTask(
        repository: InMemoryTaskRepository,
        key: String,
        acquiredAt: Instant,
        leaseUntil: Instant,
    ): LifeTask {
        val createdAt = acquiredAt.minusSeconds(60)
        val task = LifeTask(
            type = TaskType.PROCESS_PHOTON,
            idempotencyKey = key,
            createdAt = createdAt,
            updatedAt = createdAt,
        )
        repository.create(task)
        val queued = checkNotNull(
            repository.transition(
                task.id,
                TaskState.CREATED,
                TaskState.QUEUED,
                createdAt,
            )
        )
        val claimed = checkNotNull(
            repository.claim(
                queued.id,
                WorkerId("old-worker"),
                acquiredAt,
                leaseUntil,
            )
        )
        return checkNotNull(
            repository.transition(
                claimed.id,
                TaskState.CLAIMED,
                TaskState.RUNNING,
                acquiredAt,
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
