package app.lifeos.core.runtime.workers

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.task.CreateTaskResult
import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskLoadReport
import app.lifeos.core.model.task.TaskPriority
import app.lifeos.core.model.task.TaskSnapshotRepository
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.model.task.TaskType
import app.lifeos.core.model.worker.WorkerId
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TaskSnapshotWorkerLeaseInspectorTest {
    @Test
    fun `inspector derives ownership only from durable claimedBy truth`() = runTest {
        val worker = WorkerId("worker-a")
        val other = WorkerId("worker-b")
        val at = Instant.parse("2026-09-10T09:00:00Z")
        val repository = SnapshotOnlyTaskRepository(
            TaskLoadReport(
                tasks = listOf(
                    leasedTask(TaskId("task-z"), worker, at),
                    leasedTask(TaskId("task-a"), worker, at),
                    leasedTask(TaskId("task-other"), other, at),
                    LifeTask(
                        id = TaskId("task-unowned"),
                        type = TaskType.PROCESS_PHOTON,
                        state = TaskState.QUEUED,
                        priority = TaskPriority.NORMAL,
                        inputPhotonIds = setOf(PhotonId("p-unowned")),
                        idempotencyKey = "unowned",
                        createdAt = at,
                        updatedAt = at,
                        scheduledAt = at,
                    ),
                ),
                unreadableEntries = listOf("vault-z", "vault-a"),
            )
        )

        val snapshot = TaskSnapshotWorkerLeaseInspector(repository).inspect(worker, at)

        assertEquals(worker, snapshot.workerId)
        assertEquals(listOf("task-a", "task-z"), snapshot.ownedTaskIds.map { it.value })
        assertEquals(listOf("vault-a", "vault-z"), snapshot.unreadableEntries)
        assertEquals(at, snapshot.capturedAt)
    }

    private fun leasedTask(id: TaskId, owner: WorkerId, at: Instant) = LifeTask(
        id = id,
        type = TaskType.PROCESS_PHOTON,
        state = TaskState.CLAIMED,
        priority = TaskPriority.NORMAL,
        inputPhotonIds = setOf(PhotonId("photon-${id.value}")),
        idempotencyKey = "key-${id.value}",
        attempt = 1,
        maxAttempts = 3,
        createdAt = at,
        updatedAt = at,
        scheduledAt = at,
        claimedBy = owner,
        leaseExpiresAt = at.plusSeconds(30),
    )

    private class SnapshotOnlyTaskRepository(
        private val report: TaskLoadReport,
    ) : TaskSnapshotRepository {
        override suspend fun loadReport(): TaskLoadReport = report
        override suspend fun create(task: LifeTask): CreateTaskResult = unsupported()
        override suspend fun get(id: TaskId): LifeTask? = unsupported()
        override suspend fun findByIdempotencyKey(key: String): LifeTask? = unsupported()
        override suspend fun listRunnable(now: Instant, limit: Int): List<LifeTask> = unsupported()
        override suspend fun listExpiredLeases(now: Instant, limit: Int): List<LifeTask> = unsupported()
        override suspend fun transition(id: TaskId, expected: TaskState, next: TaskState, at: Instant): LifeTask? = unsupported()
        override suspend fun claim(id: TaskId, workerId: WorkerId, acquiredAt: Instant, leaseUntil: Instant): LifeTask? = unsupported()
        override suspend fun startExecution(id: TaskId, workerId: WorkerId, startedAt: Instant): LifeTask? = unsupported()
        override suspend fun finishExecution(id: TaskId, workerId: WorkerId, finalState: TaskState, finishedAt: Instant): LifeTask? = unsupported()
        override suspend fun interruptExecution(id: TaskId, workerId: WorkerId, interruptedAt: Instant): LifeTask? = unsupported()
        override suspend fun scheduleRetry(id: TaskId, workerId: WorkerId, retryAt: Instant, scheduledAt: Instant): LifeTask? = unsupported()
        override suspend fun renewLease(id: TaskId, workerId: WorkerId, renewedAt: Instant, leaseUntil: Instant): LifeTask? = unsupported()
        override suspend fun interruptExpiredLease(
            id: TaskId,
            expectedState: TaskState,
            expectedWorkerId: WorkerId,
            expectedLeaseExpiresAt: Instant,
            at: Instant,
        ): LifeTask? = unsupported()

        private fun <T> unsupported(): T = error("not used by lease inspector test")
    }
}
