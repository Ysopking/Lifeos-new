package app.lifeos.next

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lifeos.core.data.task.EncryptedTaskRepository
import app.lifeos.core.model.task.CreateTaskResult
import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskPriority
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.model.task.TaskType
import app.lifeos.core.model.worker.WorkerId
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskIndexRecoveryTest {
    private val context
        get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val t0 = Instant.parse("2026-09-18T12:00:00Z")

    @Before
    fun before() = clearTaskVault()

    @After
    fun after() = clearTaskVault()

    @Test
    fun destroyedIndexRebuildsFromEncryptedTaskPayloads() = runBlocking {
        val repository = EncryptedTaskRepository(context)
        val task = task("b103-recovery", "b103:key:recovery", TaskPriority.HIGH)

        assertTrue(repository.create(task) is CreateTaskResult.Created)
        assertTrue(
            repository.transition(
                id = task.id,
                expected = TaskState.CREATED,
                next = TaskState.QUEUED,
                at = t0.plusSeconds(1),
            ) != null
        )

        val index = context.filesDir.resolve("task-vault/task-index.v1")
        assertTrue(index.exists())
        index.writeBytes(byteArrayOf(1, 2, 3, 4))

        val recovered = EncryptedTaskRepository(context)
        assertEquals(task.id, recovered.findByIdempotencyKey(task.idempotencyKey)?.id)
        assertEquals(listOf(task.id), recovered.listRunnable(t0.plusSeconds(2)).map { it.id })
        assertEquals(1, recovered.activeCount(setOf(TaskType.PROCESS_PHOTON)))

        val report = recovered.rebuildIndex()
        assertFalse(report.isCorrupted)
        assertEquals(1, report.taskCount)
        assertEquals(1, report.activeTaskCount)
    }

    @Test
    fun dirtyMarkerForcesCrashWindowReconciliation() = runBlocking {
        val repository = EncryptedTaskRepository(context)
        val task = task("b103-dirty", "b103:key:dirty", TaskPriority.NORMAL)

        repository.create(task)
        repository.transition(
            id = task.id,
            expected = TaskState.CREATED,
            next = TaskState.QUEUED,
            at = t0.plusSeconds(1),
        )

        context.filesDir.resolve("task-vault/task-index.dirty")
            .writeBytes("simulated-crash".toByteArray())

        val recovered = EncryptedTaskRepository(context)
        assertEquals(1, recovered.activeCount(setOf(TaskType.PROCESS_PHOTON)))
        assertFalse(context.filesDir.resolve("task-vault/task-index.dirty").exists())
        assertEquals(task.id, recovered.findByIdempotencyKey(task.idempotencyKey)?.id)
    }

    @Test
    fun hotPathsIgnoreUnrelatedUnreadableTaskUntilExplicitIntegrityScan() = runBlocking {
        val repository = EncryptedTaskRepository(context)
        val first = task("b103-hot-a", "b103:key:hot-a", TaskPriority.CRITICAL)
        val second = task("b103-hot-b", "b103:key:hot-b", TaskPriority.NORMAL)

        repository.create(first)
        repository.transition(
            id = first.id,
            expected = TaskState.CREATED,
            next = TaskState.QUEUED,
            at = t0.plusSeconds(1),
        )

        context.filesDir.resolve("task-vault/unreadable.task").writeBytes(byteArrayOf(9, 8, 7))

        assertEquals(first.id, repository.findByIdempotencyKey(first.idempotencyKey)?.id)
        assertEquals(listOf(first.id), repository.listRunnable(t0.plusSeconds(2)).map { it.id })
        assertEquals(1, repository.activeCount(setOf(TaskType.PROCESS_PHOTON)))
        assertTrue(repository.create(second) is CreateTaskResult.Created)

        val integrity = repository.loadReport()
        assertTrue(integrity.unreadableEntries.any { it == "unreadable.task" })
    }

    @Test
    fun stateTransitionsKeepActiveAndLeaseIndexesCurrent() = runBlocking {
        val repository = EncryptedTaskRepository(context)
        val task = task("b103-state", "b103:key:state", TaskPriority.INTERACTIVE)
        val worker = WorkerId("b103-worker")

        repository.create(task)
        repository.transition(
            id = task.id,
            expected = TaskState.CREATED,
            next = TaskState.QUEUED,
            at = t0.plusSeconds(1),
        )
        assertEquals(1, repository.activeCount(setOf(TaskType.PROCESS_PHOTON)))

        repository.claim(
            id = task.id,
            workerId = worker,
            acquiredAt = t0.plusSeconds(2),
            leaseUntil = t0.plusSeconds(20),
        )
        assertTrue(repository.listExpiredLeases(t0.plusSeconds(10)).isEmpty())
        assertEquals(listOf(task.id), repository.listExpiredLeases(t0.plusSeconds(21)).map { it.id })

        repository.startExecution(
            id = task.id,
            workerId = worker,
            startedAt = t0.plusSeconds(3),
        )
        repository.finishExecution(
            id = task.id,
            workerId = worker,
            finalState = TaskState.COMPLETED,
            finishedAt = t0.plusSeconds(4),
        )

        assertEquals(0, repository.activeCount(setOf(TaskType.PROCESS_PHOTON)))
        assertTrue(repository.listExpiredLeases(t0.plusSeconds(30)).isEmpty())
    }

    private fun task(id: String, key: String, priority: TaskPriority): LifeTask = LifeTask(
        id = TaskId(id),
        type = TaskType.PROCESS_PHOTON,
        state = TaskState.CREATED,
        priority = priority,
        idempotencyKey = key,
        createdAt = t0,
        updatedAt = t0,
    )

    private fun clearTaskVault() {
        context.filesDir.resolve("task-vault").deleteRecursively()
    }
}
