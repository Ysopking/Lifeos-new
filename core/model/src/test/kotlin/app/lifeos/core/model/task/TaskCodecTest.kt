package app.lifeos.core.model.task

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.worker.WorkerId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TaskCodecTest {
    @Test
    fun roundTripPreservesClaimedTask() {
        val created = Instant.parse("2026-09-07T10:00:00Z")
        val task = LifeTask(
            id = TaskId("task-1"),
            type = TaskType.PROCESS_PHOTON,
            state = TaskState.CLAIMED,
            priority = TaskPriority.INTERACTIVE,
            inputPhotonIds = setOf(PhotonId("photon-b"), PhotonId("photon-a")),
            idempotencyKey = "process:photon-a:1",
            attempt = 1,
            maxAttempts = 4,
            createdAt = created,
            updatedAt = created.plusSeconds(2),
            scheduledAt = created.plusSeconds(1),
            claimedBy = WorkerId("worker-a"),
            leaseExpiresAt = created.plusSeconds(32),
        )

        val decoded = TaskCodec.decode(TaskCodec.encode(task))

        assertEquals(task, decoded)
    }

    @Test
    fun trailingBytesAreRejected() {
        val task = LifeTask(
            type = TaskType.RECOVERY,
            idempotencyKey = "recovery:1",
        )
        val encoded = TaskCodec.encode(task) + byteArrayOf(1)

        assertFailsWith<IllegalArgumentException> {
            TaskCodec.decode(encoded)
        }
    }

    @Test
    fun unsupportedVersionIsRejected() {
        val task = LifeTask(
            type = TaskType.REBUILD_MATRIX,
            idempotencyKey = "matrix:1",
        )

        assertFailsWith<IllegalArgumentException> {
            TaskCodec.decode(TaskCodec.encode(task), version = 2)
        }
    }
}
