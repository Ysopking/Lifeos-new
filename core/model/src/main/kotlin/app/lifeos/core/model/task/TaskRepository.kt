package app.lifeos.core.model.task

import app.lifeos.core.model.worker.WorkerId
import java.time.Instant

sealed interface CreateTaskResult {
    data class Created(val task: LifeTask) : CreateTaskResult
    data class Existing(val task: LifeTask) : CreateTaskResult
}

data class TaskLoadReport(
    val tasks: List<LifeTask>,
    val unreadableEntries: List<String>,
) {
    init {
        require(tasks.map { it.id }.distinct().size == tasks.size) {
            "Task load report must not contain duplicate task ids"
        }
        require(unreadableEntries.distinct().size == unreadableEntries.size) {
            "Unreadable task entries must be unique"
        }
    }
}

interface TaskRepository {
    suspend fun create(task: LifeTask): CreateTaskResult

    suspend fun get(id: TaskId): LifeTask?

    suspend fun findByIdempotencyKey(key: String): LifeTask?

    suspend fun listRunnable(now: Instant, limit: Int = 100): List<LifeTask>

    suspend fun listExpiredLeases(now: Instant, limit: Int = 100): List<LifeTask>

    suspend fun transition(
        id: TaskId,
        expected: TaskState,
        next: TaskState,
        at: Instant,
    ): LifeTask?

    suspend fun claim(
        id: TaskId,
        workerId: WorkerId,
        acquiredAt: Instant,
        leaseUntil: Instant,
    ): LifeTask?

    suspend fun startExecution(
        id: TaskId,
        workerId: WorkerId,
        startedAt: Instant,
    ): LifeTask?

    suspend fun finishExecution(
        id: TaskId,
        workerId: WorkerId,
        finalState: TaskState,
        finishedAt: Instant,
    ): LifeTask?

    suspend fun interruptExecution(
        id: TaskId,
        workerId: WorkerId,
        interruptedAt: Instant,
    ): LifeTask?

    suspend fun scheduleRetry(
        id: TaskId,
        workerId: WorkerId,
        retryAt: Instant,
        scheduledAt: Instant,
    ): LifeTask?

    suspend fun renewLease(
        id: TaskId,
        workerId: WorkerId,
        renewedAt: Instant,
        leaseUntil: Instant,
    ): LifeTask?

    suspend fun interruptExpiredLease(
        id: TaskId,
        expectedState: TaskState,
        expectedWorkerId: WorkerId,
        expectedLeaseExpiresAt: Instant,
        at: Instant,
    ): LifeTask?
}

/** Read-only boot/recovery snapshot boundary; ordinary task implementations need not expose it. */
interface TaskSnapshotRepository : TaskRepository {
    suspend fun loadReport(): TaskLoadReport
}
