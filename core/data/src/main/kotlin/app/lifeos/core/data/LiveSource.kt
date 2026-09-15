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
) {
    init {
        require(deltaId.isNotBlank() && externalKey.isNotBlank())
        require(previousFingerprint?.isNotBlank() != false && newFingerprint?.isNotBlank() != false)
        require(observationRevision > 0)
    }
}

data class SourceChangeSet(val deltas: List<SourceDelta>, val nextCursor: SourceCursor)

interface LiveSourceAdapter {
    val sourceId: LiveSourceId
    suspend fun inventory(): SourceInventory
    suspend fun changesAfter(cursor: SourceCursor): SourceChangeSet
}

/** Coalescing boundary: repeated changes for one external object collapse to its newest observed delta. */
class SourceDeltaCoalescer(private val capacity: Int) {
    init { require(capacity > 0) }
    fun coalesce(deltas: Collection<SourceDelta>): List<SourceDelta> =
        deltas.groupBy { it.sourceId to it.externalKey }
            .values.map { group -> group.maxBy { it.observationRevision } }
            .sortedWith(compareBy<SourceDelta> { it.observationRevision }.thenBy { it.deltaId })
            .takeLast(capacity)
}
