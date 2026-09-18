package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.Provenance
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Photon-authoritative runtime event journal with a reconstructible offset index.
 *
 * EVENT photons remain the historical source of truth. INDEX photons only accelerate append/read;
 * deleting/rebuilding them cannot change an event. Legacy journals are indexed once on first use.
 */
class PhotonBackedRuntimeEventJournal(private val store: PhotonRepository) : CognitiveEventJournal {
    private val lock = Mutex()
    private var indexReady = false
    private var cachedTail = -1L

    override suspend fun append(event: CognitiveEvent): Long = lock.withLock {
        ensureIndexLocked()

        val eventPhotonId = CognitionJournalIdentity.photonId(CognitionJournalKind.EVENT.tag, event.eventId)
        store.load(eventPhotonId)?.let { existing ->
            val old = decode(existing)
            check(old.event == event) { "Runtime event id conflict" }
            repairCommittedOffsetLocked(old.offset, event.eventId)
            return@withLock old.offset
        }

        val offset = Math.addExact(cachedTail, 1L)
        require(offset > 0L) { "Runtime event offset overflow" }

        writeIndexLocked(offset, event.eventId)

        store.save(
            cognitionJournalPhoton(
                CognitionJournalKind.EVENT,
                event.eventId,
                event.recordedAt,
                RuntimeEventJournalCodec.encode(RuntimeEventEnvelope(offset, event)),
            )
        )

        writeTailLocked(offset, event.recordedAt)
        cachedTail = offset
        offset
    }

    override suspend fun readFrom(offsetExclusive: Long, limit: Int): List<JournalEntry> = lock.withLock {
        require(offsetExclusive >= 0L) { "Journal offset must not be negative" }
        require(limit > 0) { "Journal read limit must be positive" }
        ensureIndexLocked()
        if (offsetExclusive >= cachedTail) return@withLock emptyList()

        val end = minOf(cachedTail, Math.addExact(offsetExclusive, limit.toLong()))
        buildList {
            var offset = offsetExclusive + 1L
            while (offset <= end) {
                val eventId = readIndexedEventIdLocked(offset)
                val photon = requireNotNull(
                    store.load(CognitionJournalIdentity.photonId(CognitionJournalKind.EVENT.tag, eventId))
                ) { "Runtime event index points to missing event at offset $offset" }
                val envelope = decode(photon)
                require(envelope.offset == offset && envelope.event.eventId == eventId) {
                    "Runtime event index/content mismatch at offset $offset"
                }
                add(JournalEntry(offset, envelope.event))
                offset++
            }
        }
    }

    override suspend fun size(): Long = lock.withLock {
        ensureIndexLocked()
        cachedTail
    }

    private suspend fun ensureIndexLocked() {
        if (indexReady) return
        val tail = runCatching { readTailLocked() }.getOrNull()
        if (tail == null) {
            rebuildIndexFromAuthorityLocked()
        } else {
            cachedTail = tail
            recoverPreparedNextOffsetLocked()
        }
        indexReady = true
    }

    private suspend fun recoverPreparedNextOffsetLocked() {
        val next = cachedTail + 1L
        val indexPhoton = store.load(indexPhotonId(next)) ?: return
        val eventId = decodeIndex(indexPhoton, next)
        val eventPhoton = store.load(
            CognitionJournalIdentity.photonId(CognitionJournalKind.EVENT.tag, eventId)
        )
        if (eventPhoton == null) {
            store.delete(indexPhotonId(next))
            return
        }
        val envelope = decode(eventPhoton)
        require(envelope.offset == next && envelope.event.eventId == eventId) {
            "Prepared runtime event index conflicts with event authority"
        }
        writeTailLocked(next, envelope.event.recordedAt)
        cachedTail = next
    }

    private suspend fun repairCommittedOffsetLocked(offset: Long, eventId: String) {
        require(offset > 0L)
        val index = store.load(indexPhotonId(offset))
        if (index == null) {
            writeIndexLocked(offset, eventId)
        } else {
            require(decodeIndex(index, offset) == eventId) {
                "Runtime event offset already points to another event"
            }
        }
        if (offset == cachedTail + 1L) {
            writeTailLocked(offset, Instant.now())
            cachedTail = offset
        } else {
            require(offset <= cachedTail) {
                "Existing runtime event is ahead of committed journal tail"
            }
        }
    }

    /** One-time migration/recovery path. EVENT photons remain the authority. */
    private suspend fun rebuildIndexFromAuthorityLocked() {
        val authoritative = events().sortedBy { it.offset }

        // INDEX is a projection only. Remove every partial/corrupt index artifact before rebuilding
        // so a crash during the very first append or legacy migration cannot poison offset 1.
        loadCognitionJournalPhotons(store, CognitionJournalKind.INDEX)
            .forEach { store.delete(it.id) }

        if (authoritative.isEmpty()) {
            cachedTail = 0L
            return
        }
        authoritative.forEachIndexed { index, envelope ->
            val expected = index.toLong() + 1L
            require(envelope.offset == expected) { "Runtime event authority is not contiguous" }
            writeIndexLocked(expected, envelope.event.eventId)
        }
        cachedTail = authoritative.last().offset
        writeTailLocked(cachedTail, authoritative.last().event.recordedAt)
    }

    private suspend fun writeIndexLocked(offset: Long, eventId: String) {
        require(offset > 0L && eventId.isNotBlank())
        val photon = cognitionJournalPhoton(
            kind = CognitionJournalKind.INDEX,
            stableId = offsetStableId(offset),
            at = Instant.EPOCH,
            content = "$INDEX_SCHEMA\noffset=$offset\nevent=$eventId",
        )
        val existing = store.load(photon.id)
        if (existing == null) {
            store.save(photon)
        } else {
            require(existing == photon) { "Conflicting runtime event offset index $offset" }
        }
    }

    private suspend fun readIndexedEventIdLocked(offset: Long): String =
        decodeIndex(
            requireNotNull(store.load(indexPhotonId(offset))) {
                "Missing runtime event offset index $offset"
            },
            offset,
        )

    private fun decodeIndex(photon: Photon, expectedOffset: Long): String {
        require(COGNITION_JOURNAL_ROOT_TAG in photon.tags)
        require("cognition-journal-kind:${CognitionJournalKind.INDEX.tag}" in photon.tags)
        val lines = photon.content.lineSequence().toList()
        require(lines.size == 3 && lines[0] == INDEX_SCHEMA) { "Invalid runtime event index schema" }
        val offset = lines[1].removePrefix("offset=").toLong()
        val eventId = lines[2].removePrefix("event=")
        require(offset == expectedOffset && eventId.isNotBlank()) { "Invalid runtime event offset index" }
        return eventId
    }

    private suspend fun readTailLocked(): Long? {
        val photon = store.load(tailPhotonId()) ?: return null
        require(COGNITION_JOURNAL_ROOT_TAG in photon.tags)
        require("cognition-journal-kind:${CognitionJournalKind.INDEX.tag}" in photon.tags)
        val lines = photon.content.lineSequence().toList()
        require(lines.size == 2 && lines[0] == TAIL_SCHEMA) { "Invalid runtime event tail schema" }
        return lines[1].removePrefix("offset=").toLong().also { offset ->
            require(offset > 0L)
            require(photon.revision == offset) { "Runtime event tail revision/content mismatch" }
        }
    }

    private suspend fun writeTailLocked(offset: Long, at: Instant) {
        require(offset > 0L)
        val existing = store.load(tailPhotonId())
        val photon = Photon(
            id = tailPhotonId(),
            revision = offset,
            content = "$TAIL_SCHEMA\noffset=$offset",
            mimeType = COGNITION_JOURNAL_MIME,
            phase = PhotonPhase.ARCHIVED,
            semanticMass = 0.0,
            energy = 0.0,
            provenance = Provenance("cognition-journal", "lifeos-runtime", at),
            tags = setOf(
                "internal",
                COGNITION_JOURNAL_ROOT_TAG,
                "cognition-journal-kind:${CognitionJournalKind.INDEX.tag}",
                "cognition-journal-schema:1",
                "cognition-journal-index:tail",
            ),
        )
        if (existing != null) {
            require(existing.revision < photon.revision) { "Runtime event tail revision did not advance" }
        }
        store.save(photon)
    }

    private fun indexPhotonId(offset: Long) =
        CognitionJournalIdentity.photonId(CognitionJournalKind.INDEX.tag, offsetStableId(offset))

    private fun tailPhotonId() =
        CognitionJournalIdentity.photonId(CognitionJournalKind.INDEX.tag, TAIL_STABLE_ID)

    private fun offsetStableId(offset: Long): String = "event-offset:$offset"

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

    private companion object {
        const val INDEX_SCHEMA = "runtime-event-index/v1"
        const val TAIL_SCHEMA = "runtime-event-tail/v1"
        const val TAIL_STABLE_ID = "event-tail"
    }
}
