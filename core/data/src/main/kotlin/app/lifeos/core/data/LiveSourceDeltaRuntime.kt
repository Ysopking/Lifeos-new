package app.lifeos.core.data

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.StableCognitiveIds
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LiveSourceCursorState(
    val sourceId: LiveSourceId,
    val stateRevision: Long,
    val cursor: SourceCursor,
    val lastObservationRevision: Long,
    val lastObservedAtEpochMillis: Long,
    val inventoryFingerprint: String,
) {
    init {
        require(stateRevision > 0L)
        require(lastObservationRevision >= 0L)
        require(lastObservedAtEpochMillis >= 0L)
        require(inventoryFingerprint.matches(Regex("[0-9a-f]{64}")))
    }
}

interface LiveSourceCursorRepository {
    suspend fun load(sourceId: LiveSourceId): LiveSourceCursorState?
    suspend fun compareAndSet(
        expectedStateRevision: Long,
        state: LiveSourceCursorState,
    ): Boolean
}

fun interface LiveSourcePhotonIngress {
    suspend fun ingest(photon: Photon)
}

data class LiveSourceSyncResult(
    val sourceId: LiveSourceId,
    val initialized: Boolean,
    val ingestedDeltas: Int,
    val cursor: SourceCursor,
    val stateRevision: Long,
)

/**
 * Delta-only runtime after first observation.
 *
 * Cursor advancement is deliberately last: every delta is first transformed into a deterministic
 * Photon revision and passed through canonical ingress. Replaying after process death therefore
 * repeats the exact same Photon identities/revisions instead of losing source changes.
 */
class LiveSourceDeltaRuntime(
    adapters: Collection<LiveSourceAdapter>,
    private val cursors: LiveSourceCursorRepository,
    private val ingress: LiveSourcePhotonIngress,
    coalesceCapacity: Int = 4_096,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    private val adapters = adapters.associateBy { it.sourceId }
    private val sourceMutexes = this.adapters.keys.associateWith { Mutex() }
    private val coalescer = SourceDeltaCoalescer(coalesceCapacity)

    init {
        require(adapters.isNotEmpty()) { "LiveSource runtime requires at least one adapter" }
        require(this.adapters.size == adapters.size) { "Duplicate LiveSource adapter id" }
    }

    suspend fun syncAll(): List<LiveSourceSyncResult> =
        adapters.values.sortedBy { it.sourceId.value }.map { sync(it.sourceId) }

    suspend fun sync(sourceId: LiveSourceId): LiveSourceSyncResult =
        requireNotNull(sourceMutexes[sourceId]) { "Unknown LiveSource adapter ${sourceId.value}" }.withLock {
        val adapter = requireNotNull(adapters[sourceId]) { "Unknown LiveSource adapter ${sourceId.value}" }
        val existing = cursors.load(sourceId)
        if (existing == null) {
            val inventory = adapter.inventory()
            val cursor = requireNotNull(inventory.cursor) {
                "LiveSource ${sourceId.value} cannot enter delta mode without an inventory cursor"
            }
            val initialized = LiveSourceCursorState(
                sourceId = sourceId,
                stateRevision = 1L,
                cursor = cursor,
                lastObservationRevision = 0L,
                lastObservedAtEpochMillis = nowEpochMillis().coerceAtLeast(0L),
                inventoryFingerprint = inventoryFingerprint(sourceId, inventory),
            )
            check(cursors.compareAndSet(0L, initialized)) {
                "LiveSource cursor initialization raced for ${sourceId.value}"
            }
            return LiveSourceSyncResult(
                sourceId = sourceId,
                initialized = true,
                ingestedDeltas = 0,
                cursor = cursor,
                stateRevision = initialized.stateRevision,
            )
        }

        val changeSet = adapter.changesAfter(existing.cursor)
        val coalesced = coalescer.coalesce(changeSet.deltas)
        require(coalesced.all { it.sourceId == sourceId }) {
            "LiveSource adapter emitted a delta for another source"
        }
        require(coalesced.all { it.observationRevision > existing.lastObservationRevision }) {
            "LiveSource adapter replayed or regressed an observation revision"
        }

        coalesced.forEach { delta ->
            ingress.ingest(delta.toPhoton())
        }

        val nextObservationRevision = coalesced.maxOfOrNull { it.observationRevision }
            ?: existing.lastObservationRevision
        val next = existing.copy(
            stateRevision = existing.stateRevision + 1L,
            cursor = changeSet.nextCursor,
            lastObservationRevision = nextObservationRevision,
            lastObservedAtEpochMillis = coalesced.mapNotNull { it.observedAtEpochMillis }.maxOrNull()
                ?: nowEpochMillis().coerceAtLeast(existing.lastObservedAtEpochMillis),
        )
        check(cursors.compareAndSet(existing.stateRevision, next)) {
            "LiveSource cursor CAS raced for ${sourceId.value}"
        }
        LiveSourceSyncResult(
            sourceId = sourceId,
            initialized = false,
            ingestedDeltas = coalesced.size,
            cursor = next.cursor,
            stateRevision = next.stateRevision,
        )
    }

    private fun SourceDelta.toPhoton(): Photon {
        val photonId = PhotonId(
            "live_source_" + StableCognitiveIds.fingerprint(
                "live-source-photon/v1",
                sourceId.value,
                externalKey,
            )
        )
        val deterministicObservedAt = observedAtEpochMillis
            ?.let(Instant::ofEpochMilli)
            ?: Instant.EPOCH
        return Photon(
            id = photonId,
            revision = observationRevision,
            content = payload ?: buildString {
                appendLine("source=${sourceId.value}")
                appendLine("external_key=$externalKey")
                appendLine("delta=${kind.name}")
                appendLine("previous_fingerprint=${previousFingerprint.orEmpty()}")
                append("new_fingerprint=${newFingerprint.orEmpty()}")
            },
            mimeType = mimeType ?: "application/vnd.lifeos.live-source-delta+text",
            phase = if (kind == SourceDeltaKind.DELETED) PhotonPhase.ARCHIVED else PhotonPhase.ACTIVE,
            semanticMass = if (kind == SourceDeltaKind.OBSERVED) 0.5 else 1.0,
            energy = 1.0,
            confidence = 1.0,
            provenance = Provenance(
                source = "live-source:${sourceId.value}",
                actor = "live-source-adapter",
                createdAt = deterministicObservedAt,
            ),
            tags = buildSet {
                add("live-source")
                add("live-source:${sourceId.value}")
                add("source-delta:${kind.name.lowercase()}")
                add("source-privacy:${privacyZone.name.lowercase()}")
                add(
                    "source-object:" + StableCognitiveIds.fingerprint(
                        "live-source-object/v1",
                        sourceId.value,
                        externalKey,
                    )
                )
                newFingerprint?.let { add("source-fingerprint:$it") }
            },
        )
    }

    private fun inventoryFingerprint(
        sourceId: LiveSourceId,
        inventory: SourceInventory,
    ): String = StableCognitiveIds.fingerprint(
        "live-source-inventory/v1",
        sourceId.value,
        inventory.cursor?.value.orEmpty(),
        *inventory.items.sortedBy { it.externalKey }.flatMap { item ->
            listOf(
                item.externalKey,
                item.fingerprint.orEmpty(),
                item.privacyZone.name,
            )
        }.toTypedArray(),
    )
}
