package app.lifeos.core.runtime.tasks

import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskPriority
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.model.task.TaskType
import app.lifeos.core.model.worker.WorkerId
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class TaskStartExecutionCasTest {
    private val t0 = Instant.parse("2026-09-07T20:00:00Z")
    private val workerA = WorkerId("worker-a")
    private val workerB = WorkerId("worker-b")

    @Test
    fun startExecutionRequiresCurrentOwnerAndIncrementsAttemptExactlyOnce() = runTest {
        val tasks = InMemoryTaskRepository()
        val task = createQueuedTask(tasks)
        val claimed = checkNotNull(
            tasks.claim(
                id = task.id,
                workerId = workerA,
                acquiredAt = t0,
                leaseUntil = t0.plusSeconds(30),
            )
        )

        val running = checkNotNull(
            tasks.startExecution(
                id = claimed.id,
                workerId = workerA,
                startedAt = t0.plusSeconds(1),
            )
        )

        assertEquals(TaskState.RUNNING, running.state)
        assertEquals(1, running.attempt)
        assertEquals(workerA, running.claimedBy)
        assertEquals(t0.plusSeconds(30), running.leaseExpiresAt)
        assertNull(
            tasks.startExecution(
                id = claimed.id,
                workerId = workerA,
                startedAt = t0.plusSeconds(2),
            )
        )
        assertEquals(1, tasks.get(task.id)?.attempt)
    }

    @Test
    fun staleWorkerCannotStartTaskAfterRecoveryAndReclaim() = runTest {
        val tasks = InMemoryTaskRepository()
        val task = createQueuedTask(tasks)
        val firstClaim = checkNotNull(
            tasks.claim(
                id = task.id,
                workerId = workerA,
                acquiredAt = t0,
                leaseUntil = t0.plusSeconds(5),
            )
        )

        val interrupted = checkNotNull(
            tasks.interruptExpiredLease(
                id = task.id,
                expectedState = TaskState.CLAIMED,
                expectedWorkerId = workerA,
                expectedLeaseExpiresAt = checkNotNull(firstClaim.leaseExpiresAt),
                at = t0.plusSeconds(6),
            )
        )
        val recovering = checkNotNull(
            tasks.transition(
                id = task.id,
                expected = TaskState.INTERRUPTED,
                next = TaskState.RECOVERING,
                at = t0.plusSeconds(6),
            )
        )
        assertSame(TaskState.RECOVERING, recovering.state)
        checkNotNull(
            tasks.transition(
                id = task.id,
                expected = TaskState.RECOVERING,
                next = TaskState.QUEUED,
                at = t0.plusSeconds(6),
            )
        )
        val secondClaim = checkNotNull(
            tasks.claim(
                id = task.id,
                workerId = workerB,
                acquiredAt = t0.plusSeconds(7),
                leaseUntil = t0.plusSeconds(37),
            )
        )

        assertNull(
            tasks.startExecution(
                id = task.id,
                workerId = workerA,
                startedAt = t0.plusSeconds(8),
            )
        )
        val stillClaimed = checkNotNull(tasks.get(task.id))
        assertEquals(TaskState.CLAIMED, stillClaimed.state)
        assertEquals(workerB, stillClaimed.claimedBy)
        assertEquals(0, stillClaimed.attempt)

        val running = checkNotNull(
            tasks.startExecution(
                id = secondClaim.id,
                workerId = workerB,
                startedAt = t0.plusSeconds(8),
            )
        )
        assertEquals(TaskState.RUNNING, running.state)
        assertEquals(workerB, running.claimedBy)
        assertEquals(1, running.attempt)
        assertEquals(TaskState.INTERRUPTED, interrupted.state)
    }

    @Test
    fun expiredLeaseCannotStartExecution() = runTest {
        val tasks = InMemoryTaskRepository()
        val task = createQueuedTask(tasks)
        checkNotNull(
            tasks.claim(
                id = task.id,
                workerId = workerA,
                acquiredAt = t0,
                leaseUntil = t0.plusSeconds(5),
            )
        )

        assertNull(
            tasks.startExecution(
                id = task.id,
                workerId = workerA,
                startedAt = t0.plusSeconds(5),
            )
        )
        assertEquals(0, tasks.get(task.id)?.attempt)
    }

    @Test
    fun exhaustedAttemptBudgetCannotStartExecution() = runTest {
        val tasks = InMemoryTaskRepository()
        val task = LifeTask(
            type = TaskType.PROCESS_PHOTON,
            priority = TaskPriority.NORMAL,
            idempotencyKey = "attempt-budget",
            attempt = 3,
            maxAttempts = 3,
            createdAt = t0,
            updatedAt = t0,
        )
        tasks.create(task)
        checkNotNull(
            tasks.transition(
                id = task.id,
                expected = TaskState.CREATED,
                next = TaskState.QUEUED,
                at = t0,
            )
        )
        checkNotNull(
            tasks.claim(
                id = task.id,
                workerId = workerA,
                acquiredAt = t0,
                leaseUntil = t0.plusSeconds(30),
            )
        )

        assertNull(
            tasks.startExecution(
                id = task.id,
                workerId = workerA,
                startedAt = t0.plusSeconds(1),
            )
        )
        assertEquals(TaskState.CLAIMED, tasks.get(task.id)?.state)
        assertEquals(3, tasks.get(task.id)?.attempt)
    }

    private suspend fun createQueuedTask(tasks: InMemoryTaskRepository): LifeTask {
        val task = LifeTask(
            type = TaskType.PROCESS_PHOTON,
            priority = TaskPriority.NORMAL,
            idempotencyKey = "start-cas-${System.nanoTime()}",
            createdAt = t0,
            updatedAt = t0,
        )
        tasks.create(task)
        return checkNotNull(
            tasks.transition(
                id = task.id,
                expected = TaskState.CREATED,
                next = TaskState.QUEUED,
                at = t0,
            )
        )
    }
}
