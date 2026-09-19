package app.lifeos.core.data

import app.lifeos.core.model.source.SourcePrivacyZone

@JvmInline
value class LiveSourceId(val value: String) { init { require(value.isNotBlank()) } }
@JvmInline
value class SourceCursor(val value: String) { init { require(value.isNotBlank()) } }

enum class SourceDeltaKind { CREATED, UPDATED, DELETED, MOVED, OBSERVED }

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
class SourceDeltaCapacityExceededException(
    val distinctObjectCount: Int,
    val capacity: Int,
) : IllegalStateException(
    "Live source coalesced delta capacity exceeded: " + distinctObjectCount + " > " + capacity
) {
    init {
        require(distinctObjectCount > capacity)
        require(capacity > 0)
    }
}

/**
 * Lossless coalescing boundary: repeated changes for one external object collapse to the newest
 * observed delta, but distinct objects are never silently dropped. Capacity exhaustion fails closed
 * so the durable cursor cannot advance past unprocessed source truth.
 */
class SourceDeltaCoalescer(private val capacity: Int) {
    init { require(capacity > 0) }

    fun coalesce(deltas: Collection<SourceDelta>): List<SourceDelta> {
        val coalesced = deltas.groupBy { it.sourceId to it.externalKey }
            .values.map { group -> group.maxBy { it.observationRevision } }
            .sortedWith(compareBy<SourceDelta> { it.observationRevision }.thenBy { it.deltaId })
        if (coalesced.size > capacity) {
            throw SourceDeltaCapacityExceededException(coalesced.size, capacity)
        }
        return coalesced
    }
}
