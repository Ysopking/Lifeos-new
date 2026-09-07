package app.lifeos.core.runtime.tasks

import app.lifeos.core.model.task.CreateTaskResult
import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskPriority
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.model.task.TaskType
import app.lifeos.core.model.worker.WorkerId
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class InMemoryTaskRepositoryTest {
    private val t0 = Instant.parse("2026-09-07T10:00:00Z")

    @Test
    fun createDeduplicatesByIdempotencyKey() = runTest {
        val repository = InMemoryTaskRepository()
        val first = task("same-key")
        val second = task("same-key")

        assertIs<CreateTaskResult.Created>(repository.create(first))
        val duplicate = assertIs<CreateTaskResult.Existing>(repository.create(second))

        assertEquals(first.id, duplicate.task.id)
    }

    @Test
    fun onlyOneWorkerCanClaimQueuedTask() = runTest {
        val repository = InMemoryTaskRepository()
        val task = task("claim-once")
        repository.create(task)
        repository.transition(task.id, TaskState.CREATED, TaskState.QUEUED, t0.plusSeconds(1))

        val first = async {
            repository.claim(
                task.id,
                WorkerId("worker-a"),
                t0.plusSeconds(2),
                t0.plusSeconds(32),
            )
        }
        val second = async {
            repository.claim(
                task.id,
                WorkerId("worker-b"),
                t0.plusSeconds(2),
                t0.plusSeconds(32),
            )
        }

        val claims = listOf(first.await(), second.await())
        assertEquals(1, claims.count { it != null })
    }

    @Test
    fun runnableTasksAreOrderedByPriority() = runTest {
        val repository = InMemoryTaskRepository()
        val normal = task("normal", TaskPriority.NORMAL)
        val critical = task("critical", TaskPriority.CRITICAL)

        repository.create(normal)
        repository.create(critical)
        repository.transition(normal.id, TaskState.CREATED, TaskState.QUEUED, t0.plusSeconds(1))
        repository.transition(critical.id, TaskState.CREATED, TaskState.QUEUED, t0.plusSeconds(1))

        val runnable = repository.listRunnable(t0.plusSeconds(2))
        assertEquals(listOf(critical.id, normal.id), runnable.map { it.id })
    }

    @Test
    fun terminalTransitionClearsWorkerLease() = runTest {
        val repository = InMemoryTaskRepository()
        val task = task("lease-cleared")
        repository.create(task)
        repository.transition(task.id, TaskState.CREATED, TaskState.QUEUED, t0.plusSeconds(1))
        repository.claim(
            task.id,
            WorkerId("worker-a"),
            t0.plusSeconds(2),
            t0.plusSeconds(32),
        )
        repository.transition(task.id, TaskState.CLAIMED, TaskState.RUNNING, t0.plusSeconds(3))
        val completed = repository.transition(
            task.id,
            TaskState.RUNNING,
            TaskState.COMPLETED,
            t0.plusSeconds(4),
        )

        assertNull(completed?.claimedBy)
        assertNull(completed?.leaseExpiresAt)
    }

    private fun task(
        key: String,
        priority: TaskPriority = TaskPriority.NORMAL,
    ) = LifeTask(
        type = TaskType.PROCESS_PHOTON,
        priority = priority,
        idempotencyKey = key,
        createdAt = t0,
        updatedAt = t0,
    )
}
