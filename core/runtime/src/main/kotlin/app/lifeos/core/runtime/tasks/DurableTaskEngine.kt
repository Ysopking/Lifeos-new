package app.lifeos.core.runtime.tasks

import app.lifeos.core.model.task.CreateTaskResult
import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskDraft
import app.lifeos.core.model.task.TaskRepository
import app.lifeos.core.model.task.TaskState
import java.time.Instant

class DurableTaskEngine(
    private val tasks: TaskRepository,
    private val schedulerSignal: TaskSchedulerSignal,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun submit(draft: TaskDraft): LifeTask {
        val createdAt = now()
        val candidate = LifeTask(
            type = draft.type,
            priority = draft.priority,
            inputPhotonIds = draft.inputPhotonIds,
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
        ) ?: error("Created task could not transition to QUEUED: ${task.id.value}")

        schedulerSignal.wake()
        return queued
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
