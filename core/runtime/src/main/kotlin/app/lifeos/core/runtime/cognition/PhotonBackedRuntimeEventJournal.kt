package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.PhotonRevisionRef
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PhotonBackedRuntimeEventJournal(
    private val store: PhotonRepository,
    private val journalIndex: CognitionJournalIndex? = null,
) : CognitiveEventJournal {
    private val lock = Mutex()

    override suspend fun append(event: CognitiveEvent): Long = lock.withLock {
        val id = CognitionJournalIdentity.photonId(CognitionJournalKind.EVENT.tag, event.eventId)
        store.load(id)?.let { existing ->
            val old = decode(existing)
            check(old.event == event) { "Runtime event id conflict" }
            return@withLock journalIndex
                ?.entry(CognitionJournalKind.EVENT, event.eventId)
                ?.sequence
                ?: old.offset
        }

        if (journalIndex == null) {
            val offset = (events().maxOfOrNull { it.offset } ?: 0L) + 1L
            require(offset > 0L) { "Runtime event offset overflow" }
            store.save(
                cognitionJournalPhoton(
                    CognitionJournalKind.EVENT,
                    event.eventId,
                    event.recordedAt,
                    RuntimeEventJournalCodec.encode(RuntimeEventEnvelope(offset, event)),
                )
            )
            return@withLock offset
        }

        val reservation = journalIndex.reserveNext(CognitionJournalKind.EVENT, event.eventId)
        val photon = cognitionJournalPhoton(
            CognitionJournalKind.EVENT,
            event.eventId,
            event.recordedAt,
            RuntimeEventJournalCodec.encode(RuntimeEventEnvelope(reservation.sequence, event)),
        )
        store.save(photon)
        journalIndex.commit(
            reservation = reservation,
            photonRef = PhotonRevisionRef(photon.id, photon.revision),
            recordedAt = event.recordedAt,
        )
        reservation.sequence
    }

    override suspend fun appendBatch(events: List<CognitiveEvent>): List<Long> = lock.withLock {
        if (events.isEmpty()) return@withLock emptyList()
        require(events.map { it.eventId }.distinct().size == events.size) {
            "Runtime event batch contains duplicate ids"
        }

        val existingOffsets = linkedMapOf<String, Long>()
        val missing = mutableListOf<CognitiveEvent>()
        events.forEach { event ->
            val id = CognitionJournalIdentity.photonId(CognitionJournalKind.EVENT.tag, event.eventId)
            val existing = store.load(id)
            if (existing == null) {
                missing += event
            } else {
                val decoded = decode(existing)
                check(decoded.event == event) { "Runtime event id conflict" }
                existingOffsets[event.eventId] = journalIndex
                    ?.entry(CognitionJournalKind.EVENT, event.eventId)
                    ?.sequence
                    ?: decoded.offset
            }
        }

        if (journalIndex == null) {
            var next = (events().maxOfOrNull { it.offset } ?: 0L) + 1L
            val created = mutableMapOf<String, Long>()
            missing.forEach { event ->
                require(next > 0L) { "Runtime event offset overflow" }
                store.save(
                    cognitionJournalPhoton(
                        CognitionJournalKind.EVENT,
                        event.eventId,
                        event.recordedAt,
                        RuntimeEventJournalCodec.encode(RuntimeEventEnvelope(next, event)),
                    )
                )
                created[event.eventId] = next
                next = Math.addExact(next, 1L)
            }
            return@withLock events.map { event ->
                existingOffsets[event.eventId]
                    ?: checkNotNull(created[event.eventId])
            }
        }

        val reservations = journalIndex.reserveBatch(
            CognitionJournalKind.EVENT,
            missing.map { it.eventId },
        )
        val photons = reservations.zip(missing).map { (reservation, event) ->
            cognitionJournalPhoton(
                CognitionJournalKind.EVENT,
                event.eventId,
                event.recordedAt,
                RuntimeEventJournalCodec.encode(RuntimeEventEnvelope(reservation.sequence, event)),
            )
        }
        photons.forEach { store.save(it) }
        journalIndex.commitBatch(
            reservations.indices.map { index ->
                Triple(
                    reservations[index],
                    PhotonRevisionRef(photons[index].id, photons[index].revision),
                    missing[index].recordedAt,
                )
            }
        )
        val created = reservations.associate { it.stableId to it.sequence }
        events.map { event ->
            existingOffsets[event.eventId]
                ?: checkNotNull(created[event.eventId])
        }
    }

    override suspend fun readFrom(
        offsetExclusive: Long,
        limit: Int,
    ): List<JournalEntry> = lock.withLock {
        require(offsetExclusive >= 0L && limit > 0)
        if (journalIndex == null) {
            return@withLock events()
                .filter { it.offset > offsetExclusive }
                .sortedBy { it.offset }
                .take(limit)
                .map { JournalEntry(it.offset, it.event) }
        }

        val entries = journalIndex.entries(CognitionJournalKind.EVENT)
            .asSequence()
            .filter { it.sequence > offsetExclusive }
            .take(limit)
            .toList()
        entries.zip(loadCognitionJournalPhotons(store, entries.map { it.photonRef }))
            .map { (entry, photon) ->
                val decoded = decode(photon)
                JournalEntry(entry.sequence, decoded.event)
            }
    }

    override suspend fun size(): Long = lock.withLock {
        journalIndex?.size(CognitionJournalKind.EVENT) ?: events().size.toLong()
    }

    private suspend fun events(): List<RuntimeEventEnvelope> =
        loadCognitionJournalPhotons(store, CognitionJournalKind.EVENT).map(::decode)

    private fun decode(photon: Photon): RuntimeEventEnvelope = try {
        require(photon.mimeType == COGNITION_JOURNAL_MIME)
        RuntimeEventJournalCodec.decode(photon.content)
    } catch (error: Exception) {
        throw CognitionJournalCorruptionException(
            "Unreadable runtime event journal ${photon.id.value}",
            error,
        )
    }
}
