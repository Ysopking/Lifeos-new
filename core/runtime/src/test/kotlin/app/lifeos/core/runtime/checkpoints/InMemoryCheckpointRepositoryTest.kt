package app.lifeos.core.runtime.checkpoints

import app.lifeos.core.model.checkpoint.SaveCheckpointResult
import app.lifeos.core.model.checkpoint.TaskCheckpoint
import app.lifeos.core.model.task.TaskId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemoryCheckpointRepositoryTest {
    @Test fun latestReturnsHighestSequence() = runTest {
        val repository = InMemoryCheckpointRepository()
        val taskId = TaskId("task-latest")

        repository.save(TaskCheckpoint(taskId = taskId, sequence = 1, payload = byteArrayOf(1)))
        repository.save(TaskCheckpoint(taskId = taskId, sequence = 3, payload = byteArrayOf(3)))
        repository.save(TaskCheckpoint(taskId = taskId, sequence = 2, payload = byteArrayOf(2)))

        val latest = assertNotNull(repository.latest(taskId))
        assertEquals(3, latest.sequence)
        assertContentEquals(byteArrayOf(3), latest.payload)
    }

    @Test fun duplicateTaskSequenceIsIdempotent() = runTest {
        val repository = InMemoryCheckpointRepository()
        val taskId = TaskId("task-dedup")
        val first = TaskCheckpoint(taskId = taskId, sequence = 1, payload = byteArrayOf(1))
        val duplicate = TaskCheckpoint(taskId = taskId, sequence = 1, payload = byteArrayOf(9))

        assertIs<SaveCheckpointResult.Created>(repository.save(first))
        val secondResult = assertIs<SaveCheckpointResult.Existing>(repository.save(duplicate))

        assertEquals(first.id, secondResult.checkpoint.id)
        assertContentEquals(byteArrayOf(1), secondResult.checkpoint.payload)
    }

    @Test fun deleteRemovesCheckpointAndSequenceReservation() = runTest {
        val repository = InMemoryCheckpointRepository()
        val taskId = TaskId("task-delete")
        val first = TaskCheckpoint(taskId = taskId, sequence = 1, payload = byteArrayOf(1))
        repository.save(first)

        assertTrue(repository.delete(first.id))
        assertNull(repository.get(first.id))

        val replacement = TaskCheckpoint(taskId = taskId, sequence = 1, payload = byteArrayOf(2))
        assertIs<SaveCheckpointResult.Created>(repository.save(replacement))
    }
}
