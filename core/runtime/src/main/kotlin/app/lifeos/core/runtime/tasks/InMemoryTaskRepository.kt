package app.lifeos.core.runtime.tasks

import app.lifeos.core.model.task.CreateTaskResult
import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskRepository
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.model.task.TaskStateMachine
import app.lifeos.core.model.worker.WorkerId
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemoryTaskRepository : TaskRepository {
    private val mutex = Mutex()
    private val tasks = linkedMapOf<TaskId, LifeTask>()
    private val idempotencyIndex = mutableMapOf<String, TaskId>()

    override suspend fun create(task: LifeTask): CreateTaskResult = mutex.withLock {
        require(task.state == TaskState.CREATED) { "New repository task must be CREATED" }

        idempotencyIndex[task.idempotencyKey]?.let { existingId ->
            return@withLock CreateTaskResult.Existing(checkNotNull(tasks[existingId]))
        }

        check(task.id !in tasks) { "Task ID already exists: ${task.id.value}" }
        tasks[task.id] = task
        idempotencyIndex[task.idempotencyKey] = task.id
        CreateTaskResult.Created(task)
    }

    override suspend fun get(id: TaskId): LifeTask? = mutex.withLock {
        tasks[id]
    }

    override suspend fun findByIdempotencyKey(key: String): LifeTask? = mutex.withLock {
        idempotencyIndex[key]?.let(tasks::get)
    }

    override suspend fun listRunnable(now: Instant, limit: Int): List<LifeTask> = mutex.withLock {
        require(limit > 0) { "Runnable task limit must be positive" }
        tasks.values
            .asSequence()
            .filter { task ->
                task.state == TaskState.QUEUED ||
                    (task.state == TaskState.RETRY_WAIT &&
                        (task.scheduledAt == null || !task.scheduledAt.isAfter(now)))
            }
            .sortedWith(
                compareByDescending<LifeTask> { it.priority.weight }
                    .thenBy { it.createdAt }
            )
            .take(limit)
            .toList()
    }

    override suspend fun transition(
        id: TaskId,
        expected: TaskState,
        next: TaskState,
        at: Instant,
    ): LifeTask? = mutex.withLock {
        require(next != TaskState.CLAIMED) { "Use claim() for QUEUED -> CLAIMED" }
        val current = tasks[id] ?: return@withLock null
        if (current.state != expected) return@withLock null

        TaskStateMachine.requireTransition(expected, next)
        val keepsLease = next == TaskState.RUNNING || next == TaskState.CHECKPOINTED
        val updated = current.copy(
            state = next,
            updatedAt = at,
            claimedBy = if (keepsLease) current.claimedBy else null,
            leaseExpiresAt = if (keepsLease) current.leaseExpiresAt else null,
        )
        tasks[id] = updated
        updated
    }

    override suspend fun claim(
        id: TaskId,
        workerId: WorkerId,
        acquiredAt: Instant,
        leaseUntil: Instant,
    ): LifeTask? = mutex.withLock {
        require(leaseUntil.isAfter(acquiredAt)) { "Task lease must expire after acquisition" }
        val current = tasks[id] ?: return@withLock null
        if (current.state != TaskState.QUEUED) return@withLock null

        TaskStateMachine.requireTransition(TaskState.QUEUED, TaskState.CLAIMED)
        val claimed = current.copy(
            state = TaskState.CLAIMED,
            updatedAt = acquiredAt,
            claimedBy = workerId,
            leaseExpiresAt = leaseUntil,
        )
        tasks[id] = claimed
        claimed
    }
}
