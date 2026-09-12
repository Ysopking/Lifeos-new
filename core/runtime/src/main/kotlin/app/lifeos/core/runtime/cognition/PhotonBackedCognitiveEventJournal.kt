package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.PhotonRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PhotonBackedCognitiveEventJournal(
    private val repository: PhotonRepository,
) : CognitiveEventJournal {
    private val mutex = Mutex()

    override suspend fun append(event: CognitiveEvent): Long = mutex.withLock {
        val existing = loadCognitionJournalPhoton(repository, CognitionJournalKind.EVENT, event.eventId)
        if (existing != null) {
            val decoded = RuntimeEventJournalCodec.decode(existing.content)
            check(decoded.event == event) { "Conflicting cognitive event id: ${event.eventId}" }
            return@withLock decoded.offset
        }

        val offset = loadCognitionJournalPhotons(repository, CognitionJournalKind.EVENT)
            .map { RuntimeEventJournalCodec.decode(it.content).offset }
            .maxOrNull()
            ?.plus(1L)
            ?: 1L
        val payload = RuntimeEventJournalCodec.encode(RuntimeEventEnvelope(offset, event))
        repository.save(cognitionJournalPhoton(CognitionJournalKind.EVENT, event.eventId, event.recordedAt, payload))
        offset
    }

    override suspend fun readFrom(offsetExclusive: Long, limit: Int): List<JournalEntry> = mutex.withLock {
        require(offsetExclusive >= 0L)
        require(limit > 0)
        loadCognitionJournalPhotons(repository, CognitionJournalKind.EVENT)
            .map { RuntimeEventJournalCodec.decode(it.content) }
            .sortedBy { it.offset }
            .filter { it.offset > offsetExclusive }
            .take(limit)
            .map { JournalEntry(it.offset, it.event) }
    }

    override suspend fun size(): Long = mutex.withLock {
        loadCognitionJournalPhotons(repository, CognitionJournalKind.EVENT).size.toLong()
    }
}
