package app.lifeos.core.data.task

import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskPriority
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.model.task.TaskType
import java.time.Instant
import javax.crypto.KeyGenerator
import kotlin.test.Test
import kotlin.test.assertEquals

class TaskIndexCodecTest {
    private val t0 = Instant.parse("2026-09-18T12:00:00Z")

    @Test
    fun codecRoundTripPreservesIndexedQueries() {
        val snapshot = TaskIndexSnapshot.from(
            listOf(
                task(
                    id = "queued-low",
                    key = "key-low",
                    state = TaskState.QUEUED,
                    priority = TaskPriority.NORMAL,
                    createdAt = t0,
                ),
                task(
                    id = "queued-high",
                    key = "key-high",
                    state = TaskState.QUEUED,
                    priority = TaskPriority.CRITICAL,
                    createdAt = t0.plusSeconds(1),
                ),
                task(
                    id = "completed",
                    key = "key-completed",
                    state = TaskState.COMPLETED,
                    priority = TaskPriority.CRITICAL,
                    createdAt = t0.plusSeconds(2),
                ),
            )
        )

        val decoded = TaskIndexCodec.decode(TaskIndexCodec.encode(snapshot))

        assertEquals(
            listOf("queued-high", "queued-low"),
            decoded.runnable(t0.plusSeconds(10), limit = 10).map { it.id.value },
        )
        assertEquals(2, decoded.activeCount(setOf(TaskType.PROCESS_PHOTON)))
        assertEquals("queued-high", decoded.byIdempotencyKey("key-high")?.id?.value)
        assertEquals(3, decoded.report().taskCount)
    }

    @Test
    fun encryptedContainerRoundTripPreservesSnapshot() {
        val snapshot = TaskIndexSnapshot.from(
            listOf(
                task(
                    id = "encrypted",
                    key = "encrypted-key",
                    state = TaskState.RETRY_WAIT,
                    priority = TaskPriority.HIGH,
                    createdAt = t0,
                    scheduledAt = t0.plusSeconds(30),
                )
            )
        )
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        val decrypted = TaskIndexVaultCodec.decrypt(
            TaskIndexVaultCodec.encrypt(TaskIndexCodec.encode(snapshot), key),
            key,
        )
        val decoded = TaskIndexCodec.decode(decrypted)

        assertEquals("encrypted", decoded.byIdempotencyKey("encrypted-key")?.id?.value)
        assertEquals(0, decoded.runnable(t0.plusSeconds(10), 10).size)
        assertEquals(1, decoded.runnable(t0.plusSeconds(31), 10).size)
    }

    private fun task(
        id: String,
        key: String,
        state: TaskState,
        priority: TaskPriority,
        createdAt: Instant,
        scheduledAt: Instant? = null,
    ): LifeTask = LifeTask(
        id = TaskId(id),
        type = TaskType.PROCESS_PHOTON,
        state = state,
        priority = priority,
        idempotencyKey = key,
        createdAt = createdAt,
        updatedAt = createdAt,
        scheduledAt = scheduledAt,
    )
}
