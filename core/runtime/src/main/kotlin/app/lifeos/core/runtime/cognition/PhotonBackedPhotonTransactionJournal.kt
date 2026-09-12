package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PhotonBackedPhotonTransactionJournal(private val store: PhotonRepository) : PhotonTransactionJournal {
    private val lock = Mutex()

    override suspend fun record(transaction: PhotonTransactionRecord): Boolean = lock.withLock {
        val id = CognitionJournalIdentity.photonId(CognitionJournalKind.TRANSACTION.tag, transaction.transactionId)
        store.load(id)?.let {
            check(decode(it) == transaction) { "Photon transaction id conflict" }
            return@withLock false
        }
        store.save(cognitionJournalPhoton(
            CognitionJournalKind.TRANSACTION,
            transaction.transactionId,
            transaction.recordedAt,
            CognitionTransactionCodec.encode(transaction),
        ))
        true
    }

    override suspend fun get(transactionId: String): PhotonTransactionRecord? = lock.withLock {
        store.load(CognitionJournalIdentity.photonId(CognitionJournalKind.TRANSACTION.tag, transactionId))?.let(::decode)
    }

    override suspend fun latest(limit: Int): List<PhotonTransactionRecord> = lock.withLock {
        require(limit > 0)
        loadCognitionJournalPhotons(store, CognitionJournalKind.TRANSACTION).map(::decode)
            .sortedWith(compareBy<PhotonTransactionRecord> { it.recordedAt }.thenBy { it.transactionId })
            .asReversed().take(limit)
    }

    private fun decode(photon: Photon): PhotonTransactionRecord = try {
        CognitionTransactionCodec.decode(photon.content)
    } catch (error: Exception) {
        throw CognitionJournalCorruptionException("Unreadable transaction journal ${photon.id.value}", error)
    }
}
