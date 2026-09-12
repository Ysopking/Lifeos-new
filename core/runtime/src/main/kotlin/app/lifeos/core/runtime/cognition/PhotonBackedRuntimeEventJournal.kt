package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.PhotonRepository

class PhotonBackedRuntimeEventJournal(private val store: PhotonRepository) : CognitiveEventJournal {
    override suspend fun append(event: CognitiveEvent): Long = 0L
    override suspend fun readFrom(offsetExclusive: Long, limit: Int): List<JournalEntry> = emptyList()
    override suspend fun size(): Long = 0L
}
