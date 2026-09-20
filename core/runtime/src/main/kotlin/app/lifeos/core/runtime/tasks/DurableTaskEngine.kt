package app.lifeos.core.runtime.tasks

import app.lifeos.core.model.task.CreateTaskResult
import app.lifeos.core.model.task.IndexedTaskSnapshotRepository
import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskDraft
import app.lifeos.core.model.task.TaskRepository
import app.lifeos.core.model.task.TaskSnapshotRepository
import app.lifeos.core.model.task.TaskState
import java.time.Instant
import kotlinx.coroutines.sync.Mutex

class DurableTaskEngine(
    private val tasks: TaskRepository,
    private val schedulerSignal: TaskSchedulerSignal,
    private val now: () -> Instant = Instant::now,
) {
    /** Shared synchronization/snapshot boundary for all bounded cognition producers on this engine. */
    internal val cognitionAdmissionMutex = Mutex()
    internal val cognitionSnapshotRepository: IndexedTaskSnapshotRepository? = tasks as? IndexedTaskSnapshotRepository

    suspend fun submit(draft: TaskDraft): LifeTask {
        val createdAt = now()
        val candidate = LifeTask(
            type = draft.type,
            priority = draft.priority,
            inputPhotonIds = draft.inputPhotonIds,
            inputPhotonRevisions = draft.inputPhotonRevisions,
            idempotencyKey = draft.idempotencyKey,
            maxAttempts = draft.maxAttempts,
            createdAt = createdAt,
            updatedAt = createdAt,
        )

        return when (val result = tasks.create(candidate)) {
            is CreateTaskResult.Created -> queueCreated(result.task)
            is CreateTaskResult.Existing -> resumeExisting(result.task)
        }
    }

    private suspend fun queueCreated(task: LifeTask): LifeTask {
        val queued = tasks.transition(
            id = task.id,
            expected = TaskState.CREATED,
            next = TaskState.QUEUED,
            at = now(),
        )
        if (queued != null) {
            schedulerSignal.wake()
            return queued
        }

        // A concurrent duplicate submission may observe the same CREATED idempotency record and
        // win the CREATED -> QUEUED transition between create() and this CAS. Treat that as the
        // same durable task, never as a submission failure. Missing/still-CREATED state remains
        // fail-closed because no other actor can have completed the transition in that case.
        val raced = tasks.get(task.id)
        check(raced != null && raced.idempotencyKey == task.idempotencyKey) {
            "Created task disappeared before QUEUED transition: ${task.id.value}"
        }
        check(raced.state != TaskState.CREATED) {
            "Created task could not transition to QUEUED: ${task.id.value}"
        }
        if (raced.state == TaskState.QUEUED || raced.state == TaskState.RETRY_WAIT) {
            schedulerSignal.wake()
        }
        return raced
    }

    private suspend fun resumeExisting(task: LifeTask): LifeTask = when (task.state) {
        TaskState.CREATED -> queueCreated(task)

        TaskState.QUEUED,
        TaskState.RETRY_WAIT -> {
            schedulerSignal.wake()
            task
        }

        else -> task
    }
}
