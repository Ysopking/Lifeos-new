package app.lifeos.core.data

@JvmInline
value class LiveSourceId(val value: String) { init { require(value.isNotBlank()) } }
@JvmInline
value class SourceCursor(val value: String) { init { require(value.isNotBlank()) } }

enum class SourceDeltaKind { CREATED, UPDATED, DELETED, MOVED, OBSERVED }
enum class SourcePrivacyZone { PRIVATE, SENSITIVE, SHAREABLE, EPHEMERAL }

data class SourceInventoryItem(
    val externalKey: String,
    val fingerprint: String?,
    val privacyZone: SourcePrivacyZone = SourcePrivacyZone.PRIVATE,
) { init { require(externalKey.isNotBlank()); require(fingerprint?.isNotBlank() != false) } }

data class SourceInventory(val items: List<SourceInventoryItem>, val cursor: SourceCursor?)

data class SourceDelta(
    val deltaId: String,
    val sourceId: LiveSourceId,
    val externalKey: String,
    val kind: SourceDeltaKind,
    val previousFingerprint: String?,
    val newFingerprint: String?,
    val observationRevision: Long,
    val privacyZone: SourcePrivacyZone = SourcePrivacyZone.PRIVATE,
    val payload: String? = null,
    val mimeType: String? = null,
    val observedAtEpochMillis: Long? = null,
) {
    init {
        require(deltaId.isNotBlank() && externalKey.isNotBlank())
        require(previousFingerprint?.isNotBlank() != false && newFingerprint?.isNotBlank() != false)
        require(observationRevision > 0)
        require(payload == null || payload.isNotBlank())
        require(mimeType == null || mimeType.isNotBlank())
        require(observedAtEpochMillis == null || observedAtEpochMillis >= 0L)
        if (kind == SourceDeltaKind.DELETED) {
            require(newFingerprint == null) { "Deleted source delta cannot retain a new fingerprint" }
        }
    }
}

data class SourceChangeSet(val deltas: List<SourceDelta>, val nextCursor: SourceCursor)

interface LiveSourceAdapter {
    val sourceId: LiveSourceId
    suspend fun inventory(): SourceInventory
    suspend fun changesAfter(cursor: SourceCursor): SourceChangeSet
}

/**
 * State-machine coalescing boundary. A burst collapses to the net lifecycle transition instead of
 * merely selecting the newest row:
 * CREATED->UPDATED remains CREATED, UPDATED->DELETED becomes DELETED, DELETE->CREATE becomes
 * UPDATED, and CREATE->DELETE disappears when no durable object remains.
 */
class SourceDeltaCoalescer(private val capacity: Int) {
    init { require(capacity > 0) }

    fun coalesce(deltas: Collection<SourceDelta>): List<SourceDelta> =
        deltas.groupBy { it.sourceId to it.externalKey }
            .values
            .mapNotNull(::collapse)
            .sortedWith(compareBy<SourceDelta> { it.observationRevision }.thenBy { it.deltaId })
            .takeLast(capacity)

    private fun collapse(group: List<SourceDelta>): SourceDelta? {
        val ordered = group.sortedWith(compareBy<SourceDelta> { it.observationRevision }.thenBy { it.deltaId })
        require(ordered.map { it.observationRevision }.distinct().size == ordered.size) {
            "Source delta revisions must be unique per external object"
        }
        val first = ordered.first()
        val last = ordered.last()
        require(ordered.all { it.sourceId == first.sourceId && it.externalKey == first.externalKey })

        if (first.kind == SourceDeltaKind.CREATED && last.kind == SourceDeltaKind.DELETED) {
            return null
        }

        val netKind = when {
            last.kind == SourceDeltaKind.DELETED -> SourceDeltaKind.DELETED
            first.kind == SourceDeltaKind.CREATED -> SourceDeltaKind.CREATED
            first.kind == SourceDeltaKind.DELETED -> SourceDeltaKind.UPDATED
            ordered.any { it.kind == SourceDeltaKind.MOVED } -> SourceDeltaKind.MOVED
            ordered.all { it.kind == SourceDeltaKind.OBSERVED } -> SourceDeltaKind.OBSERVED
            else -> SourceDeltaKind.UPDATED
        }

        return last.copy(
            kind = netKind,
            previousFingerprint = first.previousFingerprint,
        )
    }
}
