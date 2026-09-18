package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.PhotonRevisionRef
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PhotonBackedPhotonTransactionJournal(
    private val store: PhotonRepository,
    private val journalIndex: CognitionJournalIndex? = null,
) : PhotonTransactionJournal {
    private val lock = Mutex()

    override suspend fun record(transaction: PhotonTransactionRecord): Boolean = lock.withLock {
        val id = CognitionJournalIdentity.photonId(
            CognitionJournalKind.TRANSACTION.tag,
            transaction.transactionId,
        )
        store.load(id)?.let {
            check(decode(it) == transaction) { "Photon transaction id conflict" }
            return@withLock false
        }

        if (journalIndex == null) {
            store.save(
                cognitionJournalPhoton(
                    CognitionJournalKind.TRANSACTION,
                    transaction.transactionId,
                    transaction.recordedAt,
                    CognitionTransactionCodec.encode(transaction),
                )
            )
            return@withLock true
        }

        val reservation = journalIndex.reserveNext(
            CognitionJournalKind.TRANSACTION,
            transaction.transactionId,
        )
        val photon = cognitionJournalPhoton(
            CognitionJournalKind.TRANSACTION,
            transaction.transactionId,
            transaction.recordedAt,
            CognitionTransactionCodec.encode(transaction),
        )
        store.save(photon)
        journalIndex.commit(
            reservation = reservation,
            photonRef = PhotonRevisionRef(photon.id, photon.revision),
            recordedAt = transaction.recordedAt,
        )
        true
    }

    override suspend fun get(transactionId: String): PhotonTransactionRecord? = lock.withLock {
        store.load(
            CognitionJournalIdentity.photonId(
                CognitionJournalKind.TRANSACTION.tag,
                transactionId,
            )
        )?.let(::decode)
    }

    override suspend fun latest(limit: Int): List<PhotonTransactionRecord> = lock.withLock {
        require(limit > 0)
        if (journalIndex == null) {
            return@withLock loadCognitionJournalPhotons(
                store,
                CognitionJournalKind.TRANSACTION,
            )
                .map(::decode)
                .sortedWith(
                    compareBy<PhotonTransactionRecord> { it.recordedAt }
                        .thenBy { it.transactionId }
                )
                .asReversed()
                .take(limit)
        }

        val entries = journalIndex.entries(CognitionJournalKind.TRANSACTION)
            .asReversed()
            .take(limit)
        loadCognitionJournalPhotons(store, entries.map { it.photonRef }).map(::decode)
    }

    private fun decode(photon: Photon): PhotonTransactionRecord = try {
        require(photon.mimeType == COGNITION_JOURNAL_MIME)
        CognitionTransactionCodec.decode(photon.content)
    } catch (error: Exception) {
        throw CognitionJournalCorruptionException(
            "Unreadable transaction journal ${photon.id.value}",
            error,
        )
    }
}
