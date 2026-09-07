package app.lifeos.core.model.task

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LifeTaskTest {
    @Test
    fun defaultsAreSafeForNewTask() {
        val task = LifeTask(
            type = TaskType.PROCESS_PHOTON,
            idempotencyKey = "process:test:1",
        )

        assertEquals(TaskState.CREATED, task.state)
        assertEquals(TaskPriority.NORMAL, task.priority)
        assertEquals(0, task.attempt)
        assertEquals(3, task.maxAttempts)
    }

    @Test
    fun blankIdempotencyKeyIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            LifeTask(
                type = TaskType.PROCESS_PHOTON,
                idempotencyKey = " ",
            )
        }
    }

    @Test
    fun attemptCannotExceedMaximum() {
        assertFailsWith<IllegalArgumentException> {
            LifeTask(
                type = TaskType.RECOVERY,
                idempotencyKey = "recovery:test",
                attempt = 4,
                maxAttempts = 3,
            )
        }
    }
}
