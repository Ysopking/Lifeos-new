package app.lifeos.next

internal interface StorageChangeJournal {
    fun load(
        volumeId: String,
        relativePath: String,
    ): StorageInventoryEntry?

    fun loadChangesAfter(
        revisionExclusive: Long,
        limit: Int,
    ): List<StorageChangeEntry>

    fun loadChange(
        revision: Long,
    ): StorageChangeEntry?

    fun currentChangeRevision(): Long

    fun pruneChangesThrough(
        revisionInclusive: Long,
    ): Int
}
