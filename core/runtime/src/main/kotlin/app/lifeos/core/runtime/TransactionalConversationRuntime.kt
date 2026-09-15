package app.lifeos.core.runtime

import app.lifeos.core.model.CognitiveEvent
import app.lifeos.core.model.CognitiveEventStore
import app.lifeos.core.model.CognitiveTransaction
import app.lifeos.core.model.CognitiveTransactionState
import app.lifeos.core.model.Photon

/**
 * Product-facing turn boundary. A turn either reaches COMMITTED after invariant
 * verification or is closed as FAILED/ROLLED_BACK by the coordinator.
 */
class TransactionalConversationRuntime(
    private val transactionCoordinator: CognitiveTransactionCoordinator,
    private val eventStore: CognitiveEventStore,
    private val invariantVerifier: TransactionInvariantVerifier,
    private val processor: suspend (Photon) -> ConversationTurnResult,
) {
    suspend fun process(input: Photon): ConversationTurnResult {
        val opened = transactionCoordinator.open(input)
        return try {
            val result = processor(input)
            val candidate = transactionCoordinator.bind(opened, result)
            invariantVerifier.requireValid(candidate, result)
            result.events.forEach { eventStore.append(it) }
            transactionCoordinator.commit(candidate)
            result.copy(transaction = candidate.copy(state = CognitiveTransactionState.COMMITTED))
        } catch (error: Exception) {
            transactionCoordinator.fail(opened, error)
            throw error
        }
    }
}

data class ConversationTurnResult(
    val transaction: CognitiveTransaction,
    val outputPhotons: List<Photon>,
    val events: List<CognitiveEvent>,
)

interface CognitiveTransactionCoordinator {
    suspend fun open(input: Photon): CognitiveTransaction
    suspend fun bind(transaction: CognitiveTransaction, result: ConversationTurnResult): CognitiveTransaction
    suspend fun commit(transaction: CognitiveTransaction)
    suspend fun fail(transaction: CognitiveTransaction, cause: Throwable)
}

fun interface TransactionInvariantVerifier {
    suspend fun requireValid(transaction: CognitiveTransaction, result: ConversationTurnResult)
}
