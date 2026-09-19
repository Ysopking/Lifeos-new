package app.lifeos.core.model

import java.time.Instant

data class PhotonIndexEntry(
    val ref: PhotonRevisionRef,
    val createdAt: Instant,
    val phase: PhotonPhase,
    val mimeType: String,
    val tags: Set<String>,
    val semanticMass: Double,
    val confidence: Double,
    val contentFingerprint: String,
    val latest: Boolean,
    val tombstoned: Boolean = false,
) {
    init {
        require(mimeType.isNotBlank())
        require(semanticMass.isFinite() && semanticMass >= 0.0)
        require(confidence in 0.0..1.0)
        require(contentFingerprint.matches(Regex("[0-9a-f]{64}")))
        if (tombstoned) require(latest)
    }
}

enum class PhotonIndexOrder {
    IDENTITY,
    NEWEST_FIRST,
    OLDEST_FIRST,
    HIGHEST_SEMANTIC_MASS,
    HIGHEST_CONFIDENCE,
}

data class PhotonIndexCursor(
    val order: PhotonIndexOrder,
    val lastRef: PhotonRevisionRef,
)

data class PhotonIndexQuery(
    val ids: Set<PhotonId> = emptySet(),
    val phases: Set<PhotonPhase> = emptySet(),
    val mimeTypes: Set<String> = emptySet(),
    val allTags: Set<String> = emptySet(),
    val latestOnly: Boolean = true,
    val includeTombstoned: Boolean = false,
    val order: PhotonIndexOrder = PhotonIndexOrder.IDENTITY,
    val after: PhotonIndexCursor? = null,
    val limit: Int = DEFAULT_PAGE_LIMIT,
) {
    init {
        require(limit in 1..HARD_PAGE_LIMIT) {
            "Photon index page limit must be in 1..$HARD_PAGE_LIMIT"
        }
        require(after == null || after.order == order) {
            "Photon index cursor order must match query order"
        }
        require(mimeTypes.none { it.isBlank() })
        require(allTags.none { it.isBlank() })
    }

    companion object {
        const val DEFAULT_PAGE_LIMIT: Int = 64
        const val HARD_PAGE_LIMIT: Int = 256
    }
}

data class PhotonIndexReport(
    val formatVersion: Int,
    val entryCount: Int,
    val livePhotonCount: Int,
    val tombstonedPhotonCount: Int,
    val latestRefs: Map<PhotonId, PhotonRevisionRef>,
    val unreadableRevisionFiles: List<String> = emptyList(),
) {
    init {
        require(formatVersion > 0)
        require(entryCount >= 0)
        require(livePhotonCount >= 0)
        require(tombstonedPhotonCount >= 0)
    }
}

sealed interface PhotonRevisionWriteResult {
    val photon: Photon
    val previous: Photon?

    data class Created(
        override val photon: Photon,
    ) : PhotonRevisionWriteResult {
        override val previous: Photon? = null
    }

    data class Advanced(
        override val photon: Photon,
        override val previous: Photon,
    ) : PhotonRevisionWriteResult

    data class Idempotent(
        override val photon: Photon,
        override val previous: Photon,
    ) : PhotonRevisionWriteResult

    data class Conflict(
        override val photon: Photon,
        override val previous: Photon?,
        val reason: String,
    ) : PhotonRevisionWriteResult {
        init { require(reason.isNotBlank()) }
    }
}

interface PhotonIndexReader {
    suspend fun query(query: PhotonIndexQuery): List<PhotonRevisionRef>
    suspend fun indexReport(): PhotonIndexReport
}

data class PhotonIndexHead(
    val snapshotGeneration: Long,
    val snapshotFingerprint: String,
    val lastJournalSequence: Long,
) {
    init {
        require(snapshotGeneration >= 0L)
        require(snapshotFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "Photon index head fingerprint must be lowercase SHA-256"
        }
        require(lastJournalSequence >= 0L)
    }
}

enum class PhotonIndexChangeOperation {
    CREATE,
    ADVANCE,
    TOMBSTONE,
}

data class PhotonIndexChange(
    val sequence: Long,
    val operation: PhotonIndexChangeOperation,
    val ref: PhotonRevisionRef,
    val previousHeadRef: PhotonRevisionRef?,
    val newEntry: PhotonIndexEntry,
) {
    init {
        require(sequence > 0L)
        require(newEntry.ref == ref)
        when (operation) {
            PhotonIndexChangeOperation.CREATE -> require(previousHeadRef == null)
            PhotonIndexChangeOperation.ADVANCE -> {
                val previous = requireNotNull(previousHeadRef)
                require(previous.photonId == ref.photonId)
                require(previous.revision + 1L == ref.revision)
            }
            PhotonIndexChangeOperation.TOMBSTONE -> require(previousHeadRef == ref)
        }
    }
}

sealed interface PhotonIndexChanges {
    val currentHead: PhotonIndexHead

    data class Incremental(
        val fromHead: PhotonIndexHead,
        override val currentHead: PhotonIndexHead,
        val changes: List<PhotonIndexChange>,
    ) : PhotonIndexChanges {
        init {
            require(changes == changes.sortedBy { it.sequence })
            require(changes.map { it.sequence }.distinct().size == changes.size)
            require(changes.firstOrNull()?.sequence == fromHead.lastJournalSequence + 1L || changes.isEmpty())
            require(changes.lastOrNull()?.sequence == currentHead.lastJournalSequence || changes.isEmpty())
            if (changes.isEmpty()) {
                require(fromHead == currentHead)
            }
        }
    }

    data class SnapshotRequired(
        override val currentHead: PhotonIndexHead,
        val reason: String,
    ) : PhotonIndexChanges {
        init { require(reason.isNotBlank()) }
    }
}

/**
 * Optional extension for generation-aware incremental consumers. Implementations must return
 * SnapshotRequired rather than silently skipping journal history that has already been compacted.
 */
interface IncrementalPhotonIndexReader {
    suspend fun indexHead(): PhotonIndexHead
    suspend fun changesSince(head: PhotonIndexHead): PhotonIndexChanges
}

fun Collection<PhotonIndexEntry>.canonicalPhotonIndexOrder(): List<PhotonIndexEntry> =
    sortedWith(
        compareBy<PhotonIndexEntry> { it.ref.photonId.value }
            .thenBy { it.ref.revision }
    )

fun PhotonIndexEntry.matches(query: PhotonIndexQuery): Boolean {
    if (query.latestOnly && !latest) return false
    if (!query.includeTombstoned && tombstoned) return false
    if (query.ids.isNotEmpty() && ref.photonId !in query.ids) return false
    if (query.phases.isNotEmpty() && phase !in query.phases) return false
    if (query.mimeTypes.isNotEmpty() && mimeType !in query.mimeTypes) return false
    if (!tags.containsAll(query.allTags)) return false
    return true
}
