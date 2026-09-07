package app.lifeos.core.model.task

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.worker.WorkerId
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TaskCodecTest {
    @Test
    fun roundTripPreservesClaimedTaskAndPinnedRevisions() {
        val created = Instant.parse("2026-09-07T10:00:00Z")
        val photonA = PhotonId("photon-a")
        val photonB = PhotonId("photon-b")
        val task = LifeTask(
            id = TaskId("task-1"),
            type = TaskType.PROCESS_PHOTON,
            state = TaskState.CLAIMED,
            priority = TaskPriority.INTERACTIVE,
            inputPhotonIds = setOf(photonB, photonA),
            inputPhotonRevisions = mapOf(photonA to 3L, photonB to 7L),
            idempotencyKey = "process:photon-a:3",
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
    fun legacyV1TaskRemainsReadableWithoutRevisionPins() {
        val created = Instant.parse("2026-09-07T10:00:00Z")
        val task = LifeTask(
            id = TaskId("legacy-task"),
            type = TaskType.PROCESS_PHOTON,
            state = TaskState.QUEUED,
            priority = TaskPriority.NORMAL,
            inputPhotonIds = setOf(PhotonId("legacy-photon")),
            idempotencyKey = "legacy:process:1",
            createdAt = created,
            updatedAt = created,
        )

        val decoded = TaskCodec.decode(encodeLegacyV1(task), version = 1)

        assertEquals(task, decoded)
        assertEquals(emptyMap(), decoded.inputPhotonRevisions)
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
            TaskCodec.decode(TaskCodec.encode(task), version = TaskCodec.VERSION + 1)
        }
    }

    private fun encodeLegacyV1(task: LifeTask): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeText(task.id.value)
            out.writeText(task.type.name)
            out.writeText(task.state.name)
            out.writeText(task.priority.name)
            out.writeInt(task.inputPhotonIds.size)
            task.inputPhotonIds.sortedBy { it.value }.forEach { out.writeText(it.value) }
            out.writeText(task.idempotencyKey)
            out.writeInt(task.attempt)
            out.writeInt(task.maxAttempts)
            out.writeInstant(task.createdAt)
            out.writeInstant(task.updatedAt)
            out.writeOptionalInstant(task.scheduledAt)
            out.writeOptionalText(task.claimedBy?.value)
            out.writeOptionalInstant(task.leaseExpiresAt)
        }
    }.toByteArray()

    private fun DataOutputStream.writeText(value: String) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        writeInt(encoded.size)
        write(encoded)
    }

    private fun DataOutputStream.writeInstant(value: Instant) {
        writeLong(value.epochSecond)
        writeInt(value.nano)
    }

    private fun DataOutputStream.writeOptionalInstant(value: Instant?) {
        writeBoolean(value != null)
        if (value != null) writeInstant(value)
    }

    private fun DataOutputStream.writeOptionalText(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeText(value)
    }
}
