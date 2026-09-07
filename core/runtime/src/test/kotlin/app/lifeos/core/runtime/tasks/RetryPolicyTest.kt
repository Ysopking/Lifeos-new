package app.lifeos.core.runtime.tasks

import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskType
import app.lifeos.core.runtime.RuntimeFailure
import app.lifeos.core.runtime.RuntimeFailureCategory
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RetryPolicyTest {
    private val t0 = Instant.parse("2026-09-07T18:30:00Z")

    @Test
    fun retryScheduleIsDeterministicForSameTaskAndAttempt() {
        val policy = RetryPolicy()
        val task = task(attempt = 1)
        val failures = listOf(recoverableFailure())

        val first = assertNotNull(policy.nextRetry(task, failures, t0))
        val second = assertNotNull(policy.nextRetry(task, failures, t0))

        assertEquals(first, second)
        assertTrue(first.delay >= Duration.ofMillis(1_600))
        assertTrue(first.delay <= Duration.ofMillis(2_400))
    }

    @Test
    fun backoffGrowsWithAttemptsAndRespectsMaximum() {
        val policy = RetryPolicy(
            initialDelay = Duration.ofSeconds(2),
            maxDelay = Duration.ofSeconds(8),
            jitterPermille = 0,
        )
        val failures = listOf(recoverableFailure())

        assertEquals(Duration.ofSeconds(2), assertNotNull(policy.nextRetry(task(1), failures, t0)).delay)
        assertEquals(Duration.ofSeconds(4), assertNotNull(policy.nextRetry(task(2), failures, t0)).delay)
        assertEquals(Duration.ofSeconds(8), assertNotNull(policy.nextRetry(task(3, maxAttempts = 5), failures, t0)).delay)
        assertEquals(Duration.ofSeconds(8), assertNotNull(policy.nextRetry(task(4, maxAttempts = 5), failures, t0)).delay)
    }

    @Test
    fun exhaustedAttemptBudgetDoesNotRetry() {
        val policy = RetryPolicy()

        assertNull(
            policy.nextRetry(
                task = task(attempt = 3, maxAttempts = 3),
                failures = listOf(recoverableFailure()),
                scheduledAt = t0,
            )
        )
    }

    @Test
    fun invariantAndExplicitPermanentFailuresDoNotRetry() {
        val policy = RetryPolicy()
        val task = task(attempt = 1)

        assertNull(
            policy.nextRetry(
                task,
                listOf(
                    RuntimeFailure(
                        category = RuntimeFailureCategory.INVARIANT,
                        source = "test",
                        message = "broken invariant",
                    )
                ),
                t0,
            )
        )
        assertNull(
            policy.nextRetry(
                task,
                listOf(recoverableFailure().copy(recoverable = false)),
                t0,
            )
        )
    }

    private fun task(
        attempt: Int,
        maxAttempts: Int = 3,
    ) = LifeTask(
        id = TaskId("retry-policy-task"),
        type = TaskType.PROCESS_PHOTON,
        idempotencyKey = "retry-policy",
        attempt = attempt,
        maxAttempts = maxAttempts,
        createdAt = t0,
        updatedAt = t0,
    )

    private fun recoverableFailure() = RuntimeFailure(
        category = RuntimeFailureCategory.FIELD,
        source = "field",
        message = "temporary failure",
    )
}
