package app.lifeos.core.data

import app.lifeos.core.model.StableCognitiveIds
import java.time.Instant

data class LiveSourceSnapshotState(
    val revision: Long,
    val sourceId: LiveSourceId,
    val connectorIdentityFingerprint: String,
    val inventoryFingerprint: String,
    val items: List<SourceInventoryItem>,
    val lastObservationRevision: Long,
    val updatedAt: Instant,
) {
    init {
        require(revision > 0L)
        require(connectorIdentityFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(inventoryFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(lastObservationRevision >= 0L)
        require(items == items.sortedBy { it.externalKey }) {
            "Live source snapshot items must be canonically ordered"
        }
        require(items.map { it.externalKey }.distinct().size == items.size) {
            "Live source snapshot contains duplicate external keys"
        }
        require(inventoryFingerprint == liveSourceInventoryFingerprint(sourceId, items)) {
            "Live source snapshot fingerprint does not match its inventory"
        }
    }

    fun replace(
        nextItems: Collection<SourceInventoryItem>,
        nextObservationRevision: Long,
        at: Instant,
    ): LiveSourceSnapshotState {
        require(nextObservationRevision >= lastObservationRevision)
        val canonical = canonicalLiveSourceInventory(nextItems)
        return copy(
            revision = Math.addExact(revision, 1L),
            inventoryFingerprint = liveSourceInventoryFingerprint(sourceId, canonical),
            items = canonical,
            lastObservationRevision = nextObservationRevision,
            updatedAt = at,
        )
    }

    companion object {
        fun initial(
            sourceId: LiveSourceId,
            connectorIdentityFingerprint: String,
            items: Collection<SourceInventoryItem>,
            lastObservationRevision: Long,
            at: Instant,
        ): LiveSourceSnapshotState {
            val canonical = canonicalLiveSourceInventory(items)
            return LiveSourceSnapshotState(
                revision = 1L,
                sourceId = sourceId,
                connectorIdentityFingerprint = connectorIdentityFingerprint,
                inventoryFingerprint = liveSourceInventoryFingerprint(sourceId, canonical),
                items = canonical,
                lastObservationRevision = lastObservationRevision,
                updatedAt = at,
            )
        }
    }
}

sealed interface LiveSourceSnapshotLoadResult {
    data object Missing : LiveSourceSnapshotLoadResult
    data class Loaded(val state: LiveSourceSnapshotState) : LiveSourceSnapshotLoadResult
    data class Unreadable(val message: String) : LiveSourceSnapshotLoadResult {
        init { require(message.isNotBlank()) }
    }
}

sealed interface LiveSourceSnapshotWriteResult {
    data class Saved(val state: LiveSourceSnapshotState) : LiveSourceSnapshotWriteResult
    data class Conflict(val actualRevision: Long?) : LiveSourceSnapshotWriteResult
    data class UnreadableExisting(val message: String) : LiveSourceSnapshotWriteResult {
        init { require(message.isNotBlank()) }
    }
}

interface LiveSourceSnapshotRepository {
    suspend fun load(sourceId: LiveSourceId): LiveSourceSnapshotLoadResult

    suspend fun compareAndSet(
        sourceId: LiveSourceId,
        expectedRevision: Long?,
        next: LiveSourceSnapshotState,
    ): LiveSourceSnapshotWriteResult
}

fun canonicalLiveSourceInventory(
    items: Collection<SourceInventoryItem>,
): List<SourceInventoryItem> {
    require(items.map { it.externalKey }.distinct().size == items.size) {
        "Live source inventory contains duplicate external keys"
    }
    return items.sortedBy { it.externalKey }
}

fun liveSourceInventoryFingerprint(
    sourceId: LiveSourceId,
    items: Collection<SourceInventoryItem>,
): String {
    val canonical = canonicalLiveSourceInventory(items)
    return StableCognitiveIds.fingerprint(
        "live-source-baseline/v1",
        sourceId.value,
        *canonical.flatMap {
            listOf(it.externalKey, it.fingerprint.orEmpty(), it.privacyZone.name)
        }.toTypedArray(),
    )
}

object LiveSourceSnapshotDiff {
    fun between(
        sourceId: LiveSourceId,
        previous: Collection<SourceInventoryItem>,
        current: Collection<SourceInventoryItem>,
        afterObservationRevision: Long,
    ): List<SourceDelta> {
        require(afterObservationRevision >= 0L)
        val beforeByKey = canonicalLiveSourceInventory(previous).associateBy { it.externalKey }
        val afterByKey = canonicalLiveSourceInventory(current).associateBy { it.externalKey }
        val changedKeys = (beforeByKey.keys + afterByKey.keys)
            .toSortedSet()
            .filter { key ->
                val before = beforeByKey[key]
                val after = afterByKey[key]
                when {
                    before == null || after == null -> true
                    before.fingerprint != after.fingerprint -> true
                    before.privacyZone != after.privacyZone -> true
                    else -> false
                }
            }

        return changedKeys.mapIndexed { index, key ->
            val before = beforeByKey[key]
            val after = afterByKey[key]
            val kind = when {
                before == null -> SourceDeltaKind.CREATED
                after == null -> SourceDeltaKind.DELETED
                else -> SourceDeltaKind.UPDATED
            }
            val revision = Math.addExact(afterObservationRevision, index.toLong() + 1L)
            val previousFingerprint = before?.fingerprint
            val newFingerprint = after?.fingerprint
            SourceDelta(
                deltaId = StableCognitiveIds.fingerprint(
                    "live-source-snapshot-delta/v1",
                    sourceId.value,
                    key,
                    kind.name,
                    previousFingerprint.orEmpty(),
                    newFingerprint.orEmpty(),
                    revision.toString(),
                ),
                sourceId = sourceId,
                externalKey = key,
                kind = kind,
                previousFingerprint = previousFingerprint,
                newFingerprint = newFingerprint,
                observationRevision = revision,
                privacyZone = after?.privacyZone ?: requireNotNull(before).privacyZone,
            )
        }
    }
}
