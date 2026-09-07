package app.lifeos.core.model.task

import app.lifeos.core.model.worker.WorkerId
import java.time.Instant

sealed interface CreateTaskResult {
    data class Created(val task: LifeTask) : CreateTaskResult
    data class Existing(val task: LifeTask) : CreateTaskResult
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

    suspend fun renewLease(
        id: TaskId,
        workerId: WorkerId,
        renewedAt: Instant,
        leaseUntil: Instant,
    ): LifeTask?
}
