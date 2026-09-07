package app.lifeos.core.runtime.cognition

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CognitiveEvent(
    val eventId: String = UUID.randomUUID().toString(),
    val delta: PhotonDelta,
    val recordedAt: Instant = Instant.now(),
) {
    init {
        require(eventId.isNotBlank()) { "Event id must not be blank" }
    }
}

data class JournalEntry(
    val offset: Long,
    val event: CognitiveEvent,
) {
    init {
        require(offset > 0) { "Journal offset must be positive" }
    }
}

interface CognitiveEventJournal {
    suspend fun append(event: CognitiveEvent): Long
    suspend fun readFrom(offsetExclusive: Long, limit: Int = 256): List<JournalEntry>
    suspend fun size(): Long
}

class InMemoryCognitiveEventJournal : CognitiveEventJournal {
    private val mutex = Mutex()
    private val entries = mutableListOf<JournalEntry>()
    private val offsetsByEventId = mutableMapOf<String, Long>()

    override suspend fun append(event: CognitiveEvent): Long = mutex.withLock {
        offsetsByEventId[event.eventId]?.let { return@withLock it }
        val offset = entries.size.toLong() + 1L
        entries += JournalEntry(offset, event)
        offsetsByEventId[event.eventId] = offset
        offset
    }

    override suspend fun readFrom(offsetExclusive: Long, limit: Int): List<JournalEntry> = mutex.withLock {
        require(offsetExclusive >= 0) { "Journal offset must not be negative" }
        require(limit > 0) { "Journal read limit must be positive" }
        entries.asSequence()
            .filter { it.offset > offsetExclusive }
            .take(limit)
            .toList()
    }

    override suspend fun size(): Long = mutex.withLock { entries.size.toLong() }
}
