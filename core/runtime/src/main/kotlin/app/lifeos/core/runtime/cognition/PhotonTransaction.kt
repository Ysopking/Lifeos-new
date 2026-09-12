package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.FieldInfluence
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.runtime.RuntimeFailure
import app.lifeos.core.runtime.workers.CognitiveTaskExecutionResult
import app.lifeos.core.runtime.workers.DurableTaskExecutionObserver
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class PhotonTransactionState {
    COMMITTED,
    RETRY_PENDING,
    SUPERSEDED,
    FAILED,
    INTERRUPTED,
}

data class PhotonTransactionRecord(
    val transactionId: String,
    val taskId: TaskId,
    val photonId: PhotonId?,
    val state: PhotonTransactionState,
    val influences: List<FieldInfluence>,
    val failures: List<RuntimeFailure>,
    val recordedAt: Instant,
) {
    init {
        require(transactionId.isNotBlank()) { "Photon transaction id must not be blank" }
    }
}

interface PhotonTransactionJournal {
    suspend fun record(transaction: PhotonTransactionRecord): Boolean
    suspend fun get(transactionId: String): PhotonTransactionRecord?
    suspend fun latest(limit: Int = 64): List<PhotonTransactionRecord>
}

class InMemoryPhotonTransactionJournal : PhotonTransactionJournal {
    private val mutex = Mutex()
    private val transactions = linkedMapOf<String, PhotonTransactionRecord>()

    override suspend fun record(transaction: PhotonTransactionRecord): Boolean = mutex.withLock {
        if (transaction.transactionId in transactions) return@withLock false
        transactions[transaction.transactionId] = transaction
        true
    }

    override suspend fun get(transactionId: String): PhotonTransactionRecord? = mutex.withLock {
        transactions[transactionId]
    }

    override suspend fun latest(limit: Int): List<PhotonTransactionRecord> = mutex.withLock {
        require(limit > 0) { "Photon transaction limit must be positive" }
        transactions.values.toList().asReversed().take(limit)
    }
}

/**
 * Observes the durable task execution boundary. The task repository owns the
 * atomic state/lease transition; this journal records the corresponding photon
 * transaction with field influences and failures for downstream cognition.
 */
class PhotonTransactionObserver(
    private val journal: PhotonTransactionJournal,
    private val now: () -> Instant = Instant::now,
) : DurableTaskExecutionObserver {
    override suspend fun onExecutionResult(result: CognitiveTaskExecutionResult) {
        val state = result.finalState.toTransactionState() ?: return
        journal.record(
            PhotonTransactionRecord(
                transactionId = transactionId(result.taskId, state),
                taskId = result.taskId,
                photonId = result.photonId,
                state = state,
                influences = result.influences,
                failures = result.failures,
                recordedAt = result.recordedAt ?: now(),
            )
        )
    }

    override suspend fun onDispatchFailure(task: LifeTask, error: Exception) {
        journal.record(
            PhotonTransactionRecord(
                transactionId = "photon-tx:${task.id.value}:dispatch-failed",
                taskId = task.id,
                photonId = task.inputPhotonIds.singleOrNull(),
                state = PhotonTransactionState.FAILED,
                influences = emptyList(),
                failures = emptyList(),
                recordedAt = now(),
            )
        )
    }

    private fun TaskState.toTransactionState(): PhotonTransactionState? = when (this) {
        TaskState.COMPLETED -> PhotonTransactionState.COMMITTED
        TaskState.RETRY_WAIT -> PhotonTransactionState.RETRY_PENDING
        TaskState.SUPERSEDED -> PhotonTransactionState.SUPERSEDED
        TaskState.FAILED,
        TaskState.CANCELLED -> PhotonTransactionState.FAILED
        TaskState.INTERRUPTED -> PhotonTransactionState.INTERRUPTED
        else -> null
    }

    private fun transactionId(taskId: TaskId, state: PhotonTransactionState): String =
        "photon-tx:${taskId.value}:${state.name.lowercase()}"
}
