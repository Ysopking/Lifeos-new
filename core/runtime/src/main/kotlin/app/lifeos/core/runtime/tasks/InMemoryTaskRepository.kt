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
                val scheduledAt = task.scheduledAt
                task.state == TaskState.QUEUED ||
                    (task.state == TaskState.RETRY_WAIT &&
                        (scheduledAt == null || !scheduledAt.isAfter(now)))
            }
            .sortedWith(
                compareByDescending<LifeTask> { it.priority.weight }
                    .thenBy { it.createdAt }
            )
            .take(limit)
            .toList()
    }

    override suspend fun listExpiredLeases(now: Instant, limit: Int): List<LifeTask> = mutex.withLock {
        require(limit > 0) { "Expired lease limit must be positive" }
        tasks.values
            .asSequence()
            .filter { task ->
                task.state in LEASED_STATES &&
                    task.leaseExpiresAt?.isAfter(now) == false
            }
            .sortedWith(compareBy<LifeTask> { it.leaseExpiresAt }.thenBy { it.createdAt })
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
        require(expected !in LEASED_STATES) {
            "Use owner-safe execution operations for transitions from leased task states"
        }
        val current = tasks[id] ?: return@withLock null
        if (current.state != expected) return@withLock null

        TaskStateMachine.requireTransition(expected, next)
        val updated = current.copy(
            state = next,
            updatedAt = at,
            claimedBy = null,
            leaseExpiresAt = null,
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

    override suspend fun startExecution(
        id: TaskId,
        workerId: WorkerId,
        startedAt: Instant,
    ): LifeTask? = mutex.withLock {
        val current = tasks[id] ?: return@withLock null
        if (
            current.state != TaskState.CLAIMED ||
            current.claimedBy != workerId ||
            current.leaseExpiresAt?.isAfter(startedAt) != true ||
            current.attempt >= current.maxAttempts
        ) {
            return@withLock null
        }

        TaskStateMachine.requireTransition(TaskState.CLAIMED, TaskState.RUNNING)
        val running = current.copy(
            state = TaskState.RUNNING,
            attempt = current.attempt + 1,
            updatedAt = startedAt,
        )
        tasks[id] = running
        running
    }

    override suspend fun finishExecution(
        id: TaskId,
        workerId: WorkerId,
        finalState: TaskState,
        finishedAt: Instant,
    ): LifeTask? = mutex.withLock {
        require(finalState in EXECUTION_TERMINAL_STATES) {
            "Execution can finish only as COMPLETED, SUPERSEDED, or FAILED"
        }
        val current = tasks[id] ?: return@withLock null
        if (
            current.state !in EXECUTION_STATES ||
            current.claimedBy != workerId ||
            current.leaseExpiresAt?.isAfter(finishedAt) != true
        ) {
            return@withLock null
        }

        TaskStateMachine.requireTransition(current.state, finalState)
        val finished = current.copy(
            state = finalState,
            updatedAt = finishedAt,
            scheduledAt = null,
            claimedBy = null,
            leaseExpiresAt = null,
        )
        tasks[id] = finished
        finished
    }

    override suspend fun interruptExecution(
        id: TaskId,
        workerId: WorkerId,
        interruptedAt: Instant,
    ): LifeTask? = mutex.withLock {
        val current = tasks[id] ?: return@withLock null
        if (
            current.state !in EXECUTION_STATES ||
            current.claimedBy != workerId
        ) {
            return@withLock null
        }

        TaskStateMachine.requireTransition(current.state, TaskState.INTERRUPTED)
        val interrupted = current.copy(
            state = TaskState.INTERRUPTED,
            updatedAt = interruptedAt,
            scheduledAt = null,
            claimedBy = null,
            leaseExpiresAt = null,
        )
        tasks[id] = interrupted
        interrupted
    }

    override suspend fun scheduleRetry(
        id: TaskId,
        workerId: WorkerId,
        retryAt: Instant,
        scheduledAt: Instant,
    ): LifeTask? = mutex.withLock {
        require(!retryAt.isBefore(scheduledAt)) { "Retry time must not precede scheduling time" }
        val current = tasks[id] ?: return@withLock null
        if (
            current.state !in EXECUTION_STATES ||
            current.claimedBy != workerId ||
            current.leaseExpiresAt?.isAfter(scheduledAt) != true ||
            current.attempt >= current.maxAttempts
        ) {
            return@withLock null
        }

        TaskStateMachine.requireTransition(current.state, TaskState.RETRY_WAIT)
        val retry = current.copy(
            state = TaskState.RETRY_WAIT,
            updatedAt = scheduledAt,
            scheduledAt = retryAt,
            claimedBy = null,
            leaseExpiresAt = null,
        )
        tasks[id] = retry
        retry
    }

    override suspend fun renewLease(
        id: TaskId,
        workerId: WorkerId,
        renewedAt: Instant,
        leaseUntil: Instant,
    ): LifeTask? = mutex.withLock {
        require(leaseUntil.isAfter(renewedAt)) { "Renewed task lease must expire after renewal" }
        val current = tasks[id] ?: return@withLock null
        if (
            current.state !in LEASED_STATES ||
            current.claimedBy != workerId ||
            current.leaseExpiresAt?.isAfter(renewedAt) != true
        ) {
            return@withLock null
        }

        val renewed = current.copy(
            updatedAt = renewedAt,
            leaseExpiresAt = leaseUntil,
        )
        tasks[id] = renewed
        renewed
    }

    override suspend fun interruptExpiredLease(
        id: TaskId,
        expectedState: TaskState,
        expectedWorkerId: WorkerId,
        expectedLeaseExpiresAt: Instant,
        at: Instant,
    ): LifeTask? = mutex.withLock {
        require(expectedState in LEASED_STATES) { "Expected state must hold a worker lease" }
        val current = tasks[id] ?: return@withLock null
        if (
            current.state != expectedState ||
            current.claimedBy != expectedWorkerId ||
            current.leaseExpiresAt != expectedLeaseExpiresAt ||
            current.leaseExpiresAt?.isAfter(at) != false
        ) {
            return@withLock null
        }

        TaskStateMachine.requireTransition(expectedState, TaskState.INTERRUPTED)
        val interrupted = current.copy(
            state = TaskState.INTERRUPTED,
            updatedAt = at,
            claimedBy = null,
            leaseExpiresAt = null,
        )
        tasks[id] = interrupted
        interrupted
    }

    private companion object {
        val LEASED_STATES = setOf(
            TaskState.CLAIMED,
            TaskState.RUNNING,
            TaskState.CHECKPOINTED,
        )
        val EXECUTION_STATES = setOf(
            TaskState.RUNNING,
            TaskState.CHECKPOINTED,
        )
        val EXECUTION_TERMINAL_STATES = setOf(
            TaskState.COMPLETED,
            TaskState.SUPERSEDED,
            TaskState.FAILED,
        )
    }
}
