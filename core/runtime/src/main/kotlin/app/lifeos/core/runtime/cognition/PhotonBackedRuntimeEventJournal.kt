package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.PhotonRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PhotonBackedRuntimeEventJournal(private val store: PhotonRepository) : CognitiveEventJournal {
    private val lock = Mutex()

    override suspend fun append(event: CognitiveEvent): Long = lock.withLock {
        val id = CognitionJournalIdentity.photonId(CognitionJournalKind.EVENT.tag, event.eventId)
        store.load(id)?.let { existing ->
            val old = RuntimeEventJournalCodec.decode(existing.content)
            check(old.event == event) { "Runtime event id conflict" }
            return@withLock old.offset
        }
        val offset = (events().maxOfOrNull { it.offset } ?: 0L) + 1L
        store.save(cognitionJournalPhoton(
            CognitionJournalKind.EVENT,
            event.eventId,
            event.recordedAt,
            RuntimeEventJournalCodec.encode(RuntimeEventEnvelope(offset, event)),
        ))
        offset
    }

    override suspend fun readFrom(offsetExclusive: Long, limit: Int): List<JournalEntry> = emptyList()
    override suspend fun size(): Long = 0L

    private suspend fun events(): List<RuntimeEventEnvelope> =
        loadCognitionJournalPhotons(store, CognitionJournalKind.EVENT).map { RuntimeEventJournalCodec.decode(it.content) }
}
