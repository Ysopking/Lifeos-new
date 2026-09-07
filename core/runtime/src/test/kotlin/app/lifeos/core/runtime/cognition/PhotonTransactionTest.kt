package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.runtime.workers.CognitiveTaskExecutionResult
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhotonTransactionTest {
    private val t0 = Instant.parse("2026-09-07T19:00:00Z")

    @Test
    fun completedExecutionProjectsToIdempotentCommittedTransaction() = runTest {
        val journal = InMemoryPhotonTransactionJournal()
        val observer = PhotonTransactionObserver(journal, now = { t0 })
        val result = CognitiveTaskExecutionResult(
            taskId = TaskId("task-1"),
            photonId = PhotonId("photon-1"),
            finalState = TaskState.COMPLETED,
            influences = emptyList(),
            failures = emptyList(),
        )

        observer.onExecutionResult(result)
        observer.onExecutionResult(result)

        val transaction = journal.latest().single()
        assertEquals(PhotonTransactionState.COMMITTED, transaction.state)
        assertEquals("photon-tx:task-1:committed", transaction.transactionId)
        assertEquals(t0, transaction.recordedAt)
    }

    @Test
    fun retryWaitProjectsToRetryPending() = runTest {
        val journal = InMemoryPhotonTransactionJournal()
        val observer = PhotonTransactionObserver(journal, now = { t0 })

        observer.onExecutionResult(
            CognitiveTaskExecutionResult(
                taskId = TaskId("task-retry"),
                photonId = null,
                finalState = TaskState.RETRY_WAIT,
                influences = emptyList(),
                failures = emptyList(),
            )
        )

        assertEquals(PhotonTransactionState.RETRY_PENDING, journal.latest().single().state)
    }

    @Test
    fun journalRejectsDuplicateTransactionId() = runTest {
        val journal = InMemoryPhotonTransactionJournal()
        val transaction = PhotonTransactionRecord(
            transactionId = "tx-1",
            taskId = TaskId("task-1"),
            photonId = null,
            state = PhotonTransactionState.FAILED,
            influences = emptyList(),
            failures = emptyList(),
            recordedAt = t0,
        )

        assertTrue(journal.record(transaction))
        assertFalse(journal.record(transaction))
    }
}
