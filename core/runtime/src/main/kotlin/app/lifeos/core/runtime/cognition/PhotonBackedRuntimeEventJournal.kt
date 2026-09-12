package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PhotonBackedRuntimeEventJournal(private val store: PhotonRepository) : CognitiveEventJournal {
    private val lock = Mutex()

    override suspend fun append(event: CognitiveEvent): Long = lock.withLock {
        val id = CognitionJournalIdentity.photonId(CognitionJournalKind.EVENT.tag, event.eventId)
        store.load(id)?.let { existing ->
            val old = decode(existing)
            check(old.event == event) { "Runtime event id conflict" }
            return@withLock old.offset
        }
        val offset = (events().maxOfOrNull { it.offset } ?: 0L) + 1L
        require(offset > 0L) { "Runtime event offset overflow" }
        store.save(cognitionJournalPhoton(
            CognitionJournalKind.EVENT,
            event.eventId,
            event.recordedAt,
            RuntimeEventJournalCodec.encode(RuntimeEventEnvelope(offset, event)),
        ))
        offset
    }

    override suspend fun readFrom(offsetExclusive: Long, limit: Int): List<JournalEntry> = lock.withLock {
        require(offsetExclusive >= 0L && limit > 0)
        events().filter { it.offset > offsetExclusive }.sortedBy { it.offset }.take(limit)
            .map { JournalEntry(it.offset, it.event) }
    }

    override suspend fun size(): Long = lock.withLock { events().size.toLong() }

    private suspend fun events(): List<RuntimeEventEnvelope> =
        loadCognitionJournalPhotons(store, CognitionJournalKind.EVENT).map(::decode)

    private fun decode(photon: Photon): RuntimeEventEnvelope = try {
        require(photon.mimeType == COGNITION_JOURNAL_MIME)
        RuntimeEventJournalCodec.decode(photon.content)
    } catch (error: Exception) {
        throw CognitionJournalCorruptionException("Unreadable runtime event journal ${photon.id.value}", error)
    }
}
