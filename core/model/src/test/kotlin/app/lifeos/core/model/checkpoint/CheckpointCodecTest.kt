package app.lifeos.core.model.checkpoint

import app.lifeos.core.model.task.TaskId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CheckpointCodecTest {
    @Test fun roundTripPreservesCheckpoint() {
        val checkpoint = TaskCheckpoint(
            id = CheckpointId("checkpoint-1"),
            taskId = TaskId("task-1"),
            sequence = 7,
            runtimeGeneration = 3,
            payload = byteArrayOf(1, 2, 3, 4),
            createdAt = Instant.ofEpochSecond(1_700_000_000L, 123_456_789),
        )

        val decoded = CheckpointCodec.decode(CheckpointCodec.encode(checkpoint))

        assertEquals(checkpoint.id, decoded.id)
        assertEquals(checkpoint.taskId, decoded.taskId)
        assertEquals(checkpoint.sequence, decoded.sequence)
        assertEquals(checkpoint.runtimeGeneration, decoded.runtimeGeneration)
        assertEquals(checkpoint.createdAt, decoded.createdAt)
        assertContentEquals(checkpoint.payload, decoded.payload)
    }

    @Test fun trailingDataIsRejected() {
        val checkpoint = TaskCheckpoint(
            taskId = TaskId("task-trailing"),
            sequence = 1,
            payload = byteArrayOf(9),
        )
        val encoded = CheckpointCodec.encode(checkpoint) + byteArrayOf(42)

        assertFailsWith<IllegalArgumentException> {
            CheckpointCodec.decode(encoded)
        }
    }

    @Test fun unsupportedVersionIsRejected() {
        val checkpoint = TaskCheckpoint(
            taskId = TaskId("task-version"),
            sequence = 1,
            payload = byteArrayOf(7),
        )

        assertFailsWith<IllegalArgumentException> {
            CheckpointCodec.decode(CheckpointCodec.encode(checkpoint), version = 2)
        }
    }
}
