package app.lifeos.core.runtime.tasks

import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskDraft
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.model.task.TaskType
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DurableTaskEngineTest {
    private val t0 = Instant.parse("2026-09-07T12:00:00Z")

    @Test
    fun submitPersistsQueuesAndWakesScheduler() = runTest {
        val repository = InMemoryTaskRepository()
        val signal = RecordingSignal()
        val engine = DurableTaskEngine(repository, signal) { t0 }

        val queued = engine.submit(
            TaskDraft(
                type = TaskType.PROCESS_PHOTON,
                idempotencyKey = "process:p1:r1",
            )
        )

        assertEquals(TaskState.QUEUED, queued.state)
        assertEquals(queued, repository.get(queued.id))
        assertEquals(1, signal.wakes)
    }

    @Test
    fun duplicateSubmitReturnsSameLogicalTask() = runTest {
        val repository = InMemoryTaskRepository()
        val signal = RecordingSignal()
        val engine = DurableTaskEngine(repository, signal) { t0 }
        val draft = TaskDraft(
            type = TaskType.PROCESS_PHOTON,
            idempotencyKey = "process:p1:r1",
        )

        val first = engine.submit(draft)
        val second = engine.submit(draft)

        assertEquals(first.id, second.id)
        assertEquals(TaskState.QUEUED, second.state)
        assertEquals(2, signal.wakes)
    }

    @Test
    fun duplicateSubmitRepairsPersistedCreatedTask() = runTest {
        val repository = InMemoryTaskRepository()
        val signal = RecordingSignal()
        val existing = LifeTask(
            type = TaskType.PROCESS_PHOTON,
            idempotencyKey = "process:p1:r1",
            createdAt = t0,
            updatedAt = t0,
        )
        repository.create(existing)

        val engine = DurableTaskEngine(repository, signal) { t0 }
        val resumed = engine.submit(
            TaskDraft(
                type = TaskType.PROCESS_PHOTON,
                idempotencyKey = existing.idempotencyKey,
            )
        )

        assertEquals(existing.id, resumed.id)
        assertEquals(TaskState.QUEUED, resumed.state)
        assertEquals(1, signal.wakes)
    }

    private class RecordingSignal : TaskSchedulerSignal {
        var wakes = 0
            private set

        override suspend fun wake() {
            wakes += 1
        }
    }
}
