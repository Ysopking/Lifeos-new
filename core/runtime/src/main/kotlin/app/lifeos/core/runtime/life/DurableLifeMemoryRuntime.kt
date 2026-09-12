package app.lifeos.core.runtime.life

import app.lifeos.core.model.CanonicalPhotonState
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.StableCognitiveIds
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64

/** Stable identity of one authorized source adapter. Adapter version is part of cursor identity. */
data class LifeSourceDescriptor(
    val sourceId: String,
    val adapterVersion: String,
) {
    init {
        require(sourceId.isNotBlank())
        require(adapterVersion.isNotBlank())
    }

    val fingerprint: String = StableCognitiveIds.fingerprint(
        "life-source-descriptor/v1",
        sourceId,
        adapterVersion,
    )
}

data class DurableLifeSourceCheckpoint(
    val descriptor: LifeSourceDescriptor,
    val position: String?,
    val lastBatchFingerprint: String?,
    val revision: Long,
) {
    init {
        require(revision >= 0L)
        require(lastBatchFingerprint == null || lastBatchFingerprint.isNotBlank())
    }

    val cursor: LifeSourceCursor get() = LifeSourceCursor(descriptor.sourceId, position)
}

data class DurableLifeIngestCommit(
    val descriptor: LifeSourceDescriptor,
    val cursorBefore: LifeSourceCursor,
    val cursorAfter: LifeSourceCursor,
    val evidencePhotons: List<Photon>,
    val checkpoint: DurableLifeSourceCheckpoint,
    val permissionGap: Photon? = null,
)

data class DurableLifeMemorySnapshot(
    val graph: LifeGraphSnapshot,
    val memory: LongTermMemoryProjection,
    val accessLedger: MemoryAccessLedger,
    val authoritativePhotonCount: Int,
    val fingerprint: String,
)

/** Photon-backed source cursor. Evidence is committed before this management Photon advances. */
class PhotonBackedLifeSourceCheckpointStore(
    private val photons: PhotonRepository,
) {
    suspend fun load(descriptor: LifeSourceDescriptor): DurableLifeSourceCheckpoint {
        val photon = photons.load(checkpointId(descriptor))
            ?: return DurableLifeSourceCheckpoint(descriptor, null, null, 0L)
        require("life-source-checkpoint" in photon.tags) { "Life source checkpoint tag missing" }
        val fields = parseFields(photon.content)
        require(fields["schema"] == SCHEMA) { "Unsupported life source checkpoint schema" }
        require(decode(required(fields, "source")) == descriptor.sourceId) { "Life source checkpoint source mismatch" }
        require(decode(required(fields, "adapter")) == descriptor.adapterVersion) { "Life source checkpoint adapter mismatch" }
        val position = required(fields, "position").takeIf { it != NULL }?.let(::decode)
        val lastBatch = required(fields, "batch").takeIf { it != NULL }
        return DurableLifeSourceCheckpoint(
            descriptor = descriptor,
            position = position,
            lastBatchFingerprint = lastBatch,
            revision = photon.revision,
        )
    }

    suspend fun commit(
        previous: DurableLifeSourceCheckpoint,
        nextPosition: String?,
        batchFingerprint: String,
        evidenceIds: Set<PhotonId>,
        committedAt: Instant,
    ): DurableLifeSourceCheckpoint {
        require(batchFingerprint.isNotBlank())
        if (previous.position == nextPosition && previous.lastBatchFingerprint == batchFingerprint) return previous

        val desired = DurableLifeSourceCheckpoint(
            descriptor = previous.descriptor,
            position = nextPosition,
            lastBatchFingerprint = batchFingerprint,
            revision = previous.revision + 1L,
        )
        val content = buildString {
            appendLine("schema=$SCHEMA")
            appendLine("source=${encode(previous.descriptor.sourceId)}")
            appendLine("adapter=${encode(previous.descriptor.adapterVersion)}")
            appendLine("position=${nextPosition?.let(::encode) ?: NULL}")
            append("batch=$batchFingerprint")
        }
        photons.save(
            Photon(
                id = checkpointId(previous.descriptor),
                revision = desired.revision,
                content = content,
                mimeType = MIME_TYPE,
                provenance = Provenance(
                    source = "life-source-checkpoint",
                    actor = previous.descriptor.sourceId,
                    createdAt = committedAt,
                    parentIds = evidenceIds,
                ),
                tags = setOf(
                    "life-memory-management",
                    "life-source-checkpoint",
                    "source:${previous.descriptor.sourceId}",
                    "source-adapter:${previous.descriptor.adapterVersion}",
                ),
            )
        )
        return desired
    }

    private fun checkpointId(descriptor: LifeSourceDescriptor): PhotonId = PhotonId(
        "life-source-checkpoint:" + StableCognitiveIds.fingerprint(
            "life-source-checkpoint-id/v1",
            descriptor.sourceId,
            descriptor.adapterVersion,
        )
    )

    private companion object {
        const val MIME_TYPE = "application/vnd.lifeos.life-source-checkpoint+text"
        const val SCHEMA = "1"
        const val NULL = "~"
    }
}

/** Append-only access events make last-access/count/relevance state rebuildable after process death. */
class PhotonBackedMemoryAccessLedgerStore(
    private val photons: PhotonRepository,
) {
    suspend fun recordAccess(
        photonId: PhotonId,
        accessKey: String,
        at: Instant,
        goalRelevance: Double? = null,
        relationshipWeight: Double? = null,
        futureRelevance: Double? = null,
        seinRelevance: Double? = null,
    ): Photon {
        require(accessKey.isNotBlank())
        listOfNotNull(goalRelevance, relationshipWeight, futureRelevance, seinRelevance).forEach {
            require(it.isFinite() && it in 0.0..1.0)
        }
        val id = PhotonId(
            "memory-access-event:" + StableCognitiveIds.fingerprint(
                "memory-access-event/v1",
                photonId.value,
                accessKey,
            )
        )
        val content = buildString {
            appendLine("schema=1")
            appendLine("target=${encode(photonId.value)}")
            appendLine("access_key=${encode(accessKey)}")
            appendLine("at=$at")
            appendLine("goal=${number(goalRelevance)}")
            appendLine("relationship=${number(relationshipWeight)}")
            appendLine("future=${number(futureRelevance)}")
            append("sein=${number(seinRelevance)}")
        }
        val event = Photon(
            id = id,
            content = content,
            mimeType = "application/vnd.lifeos.memory-access-event+text",
            provenance = Provenance(
                source = "life-memory-access",
                actor = "lifeos",
                createdAt = at,
                parentIds = setOf(photonId),
            ),
            tags = setOf("life-memory-management", "memory-access-event"),
        )
        saveIdempotent(event)
        return event
    }

    suspend fun snapshot(): MemoryAccessLedger {
        val events = photons.loadAll()
            .filter { "memory-access-event" in it.tags }
            .map(::decode)
            .sortedWith(compareBy<AccessEvent> { it.at }.thenBy { it.id.value })
        var ledger = MemoryAccessLedger()
        events.forEach { event ->
            ledger = ledger.recordAccess(
                photonId = event.target,
                at = event.at,
                goalRelevance = event.goalRelevance,
                relationshipWeight = event.relationshipWeight,
                futureRelevance = event.futureRelevance,
                seinRelevance = event.seinRelevance,
            )
        }
        return ledger
    }

    private data class AccessEvent(
        val id: PhotonId,
        val target: PhotonId,
        val at: Instant,
        val goalRelevance: Double?,
        val relationshipWeight: Double?,
        val futureRelevance: Double?,
        val seinRelevance: Double?,
    )

    private fun decode(photon: Photon): AccessEvent {
        val fields = parseFields(photon.content)
        require(fields["schema"] == "1") { "Unsupported memory access event schema" }
        val target = PhotonId(decode(required(fields, "target")))
        require(target in photon.provenance.parentIds) { "Memory access event target parent mismatch" }
        return AccessEvent(
            id = photon.id,
            target = target,
            at = Instant.parse(required(fields, "at")),
            goalRelevance = optionalNumber(required(fields, "goal")),
            relationshipWeight = optionalNumber(required(fields, "relationship")),
            futureRelevance = optionalNumber(required(fields, "future")),
            seinRelevance = optionalNumber(required(fields, "sein")),
        )
    }

    private suspend fun saveIdempotent(photon: Photon) {
        val existing = photons.load(photon.id)
        if (existing == null) photons.save(photon)
        else check(existing == photon) { "Conflicting memory access event identity: ${photon.id.value}" }
    }
}

/**
 * Crash-safe authorized source ingestion. Evidence is persisted before the cursor is advanced.
 * Stable record ids make a replay after process death idempotent across batches and restarts.
 */
class DurableLifeSourceIngestor(
    private val photons: PhotonRepository,
    private val checkpoints: PhotonBackedLifeSourceCheckpointStore = PhotonBackedLifeSourceCheckpointStore(photons),
) {
    suspend fun ingest(
        descriptor: LifeSourceDescriptor,
        records: Collection<LifeSourceRecord>,
        nextPosition: String?,
        authorized: Boolean,
        committedAt: Instant,
    ): DurableLifeIngestCommit {
        val before = checkpoints.load(descriptor)
        if (!authorized) {
            val gap = permissionGap(descriptor, before.cursor, committedAt)
            saveIdempotent(gap)
            return DurableLifeIngestCommit(
                descriptor = descriptor,
                cursorBefore = before.cursor,
                cursorAfter = before.cursor,
                evidencePhotons = emptyList(),
                checkpoint = before,
                permissionGap = gap,
            )
        }
        require(records.all { it.sourceId == descriptor.sourceId }) { "Life source record belongs to another source" }
        val canonicalRecords = records.groupBy { it.recordId }.map { (recordId, duplicates) ->
            require(duplicates.distinct().size == 1) { "Conflicting duplicate life source record: $recordId" }
            duplicates.single()
        }.sortedWith(compareBy<LifeSourceRecord> { it.observedAt }.thenBy { it.recordId })
        val evidence = canonicalRecords.map { record -> sourceRecordPhoton(descriptor, record) }
        evidence.forEach { saveIdempotent(it) }
        val batchFingerprint = StableCognitiveIds.fingerprint(
            "life-source-ingest-batch/v1",
            descriptor.fingerprint,
            before.position.orEmpty(),
            nextPosition.orEmpty(),
            *evidence.map { it.id.value }.toTypedArray(),
        )
        val checkpoint = checkpoints.commit(
            previous = before,
            nextPosition = nextPosition,
            batchFingerprint = batchFingerprint,
            evidenceIds = evidence.mapTo(linkedSetOf()) { it.id },
            committedAt = committedAt,
        )
        return DurableLifeIngestCommit(
            descriptor = descriptor,
            cursorBefore = before.cursor,
            cursorAfter = checkpoint.cursor,
            evidencePhotons = evidence,
            checkpoint = checkpoint,
        )
    }

    private fun sourceRecordPhoton(descriptor: LifeSourceDescriptor, record: LifeSourceRecord): Photon = Photon(
        id = PhotonId(
            "life-source-record:" + StableCognitiveIds.fingerprint(
                "life-source-record-id/v1",
                descriptor.sourceId,
                descriptor.adapterVersion,
                record.recordId,
            )
        ),
        content = record.payload,
        mimeType = record.mimeType,
        semanticMass = 0.5,
        energy = 0.5,
        confidence = record.confidence,
        provenance = Provenance(
            source = "life-source:${descriptor.sourceId}",
            actor = descriptor.adapterVersion,
            createdAt = record.observedAt,
        ),
        tags = record.tags + setOf(
            "life-ingest",
            "life-source-evidence",
            "source:${descriptor.sourceId}",
            "source-adapter:${descriptor.adapterVersion}",
            "source-record:${record.recordId}",
        ),
    )

    private fun permissionGap(
        descriptor: LifeSourceDescriptor,
        cursor: LifeSourceCursor,
        observedAt: Instant,
    ): Photon = Photon(
        id = PhotonId(
            "life-source-gap:" + StableCognitiveIds.fingerprint(
                "life-source-permission-gap/v1",
                descriptor.sourceId,
                descriptor.adapterVersion,
                cursor.position.orEmpty(),
                observedAt.toString(),
            )
        ),
        content = buildString {
            appendLine("source=${descriptor.sourceId}")
            appendLine("adapter=${descriptor.adapterVersion}")
            appendLine("cursor=${cursor.position.orEmpty()}")
            append("state=UNAUTHORIZED")
        },
        mimeType = "application/vnd.lifeos.source-gap+text",
        semanticMass = 0.4,
        energy = 0.1,
        confidence = 1.0,
        provenance = Provenance(
            source = "life-source-permission",
            actor = descriptor.sourceId,
            createdAt = observedAt,
        ),
        tags = setOf(
            "life-source-gap",
            "permission-state:unauthorized",
            "source:${descriptor.sourceId}",
            "source-adapter:${descriptor.adapterVersion}",
        ),
    )

    private suspend fun saveIdempotent(photon: Photon) {
        val existing = photons.load(photon.id)
        if (existing == null) {
            photons.save(photon)
        } else {
            check(existing == photon) {
                "Conflicting durable life source identity: ${photon.id.value}; source record changed without adapter/version identity change"
            }
        }
    }
}

/** Productive boot/continuous-memory coordinator over the encrypted authoritative Photon repository. */
class DurableLifeMemoryRuntime(
    private val photons: PhotonRepository,
    private val ingestor: DurableLifeSourceIngestor = DurableLifeSourceIngestor(photons),
    private val accessStore: PhotonBackedMemoryAccessLedgerStore = PhotonBackedMemoryAccessLedgerStore(photons),
    private val graphProjector: LifeGraphProjector = LifeGraphProjector(),
    private val memoryEngine: LongTermMemoryEngine = LongTermMemoryEngine(),
) {
    suspend fun rebuild(now: Instant): DurableLifeMemorySnapshot {
        val all = photons.loadAll()
        val authoritative = all.filterNot(::isLifeMemoryManagementPhoton)
        val accessLedger = accessStore.snapshot()
        val graph = graphProjector.project(authoritative)
        val rawMemory = memoryEngine.project(authoritative, accessLedger, now)
        val memory = stabilize(rawMemory, authoritative)
        memory.derivedPhotons.forEach(::saveIdempotent)
        val fingerprint = StableCognitiveIds.fingerprint(
            "durable-life-memory-snapshot/v1",
            graph.fingerprint,
            memory.fingerprint,
            *accessLedger.profiles.entries.sortedBy { it.key.value }.flatMap { (id, profile) ->
                listOf(
                    id.value,
                    profile.lastAccessAt.toString(),
                    profile.accessCount.toString(),
                    java.lang.Double.toHexString(profile.goalRelevance),
                    java.lang.Double.toHexString(profile.relationshipWeight),
                    java.lang.Double.toHexString(profile.futureRelevance),
                    java.lang.Double.toHexString(profile.seinRelevance),
                )
            }.toTypedArray(),
        )
        return DurableLifeMemorySnapshot(
            graph = graph,
            memory = memory,
            accessLedger = accessLedger,
            authoritativePhotonCount = authoritative.size,
            fingerprint = fingerprint,
        )
    }

    suspend fun ingest(
        descriptor: LifeSourceDescriptor,
        records: Collection<LifeSourceRecord>,
        nextPosition: String?,
        authorized: Boolean,
        committedAt: Instant,
    ): Pair<DurableLifeIngestCommit, DurableLifeMemorySnapshot> {
        val commit = ingestor.ingest(descriptor, records, nextPosition, authorized, committedAt)
        return commit to rebuild(committedAt)
    }

    suspend fun recordAccess(
        photonId: PhotonId,
        accessKey: String,
        at: Instant,
        goalRelevance: Double? = null,
        relationshipWeight: Double? = null,
        futureRelevance: Double? = null,
        seinRelevance: Double? = null,
    ): DurableLifeMemorySnapshot {
        accessStore.recordAccess(
            photonId = photonId,
            accessKey = accessKey,
            at = at,
            goalRelevance = goalRelevance,
            relationshipWeight = relationshipWeight,
            futureRelevance = futureRelevance,
            seinRelevance = seinRelevance,
        )
        return rebuild(at)
    }

    private fun stabilize(
        raw: LongTermMemoryProjection,
        authoritative: List<Photon>,
    ): LongTermMemoryProjection {
        val sources = authoritative.associateBy { it.id }
        val derived = raw.derivedPhotons.map { photon ->
            val sourceParents = photon.provenance.parentIds.mapNotNull(sources::get)
            val deterministicCreatedAt = when {
                sourceParents.isEmpty() -> photon.provenance.createdAt
                "memory-crystal" in photon.tags -> sourceParents.maxOf { it.provenance.createdAt }
                else -> sourceParents.minOf { it.provenance.createdAt }
            }
            val stateTags = sourceParents.mapTo(linkedSetOf()) { source ->
                "source-state:${CanonicalPhotonState.inputHash(source).value}"
            }
            photon.copy(
                provenance = photon.provenance.copy(createdAt = deterministicCreatedAt),
                tags = photon.tags + stateTags + setOf(
                    "life-memory-management",
                    "producer-version:${LongTermMemoryEngine.RUNTIME_VERSION}",
                ),
            )
        }.sortedBy { it.id.value }
        val stableFingerprint = StableCognitiveIds.fingerprint(
            "long-term-memory-durable-projection/v1",
            *raw.decisions.sortedBy { it.photonId.value }.flatMap { decision ->
                listOf(decision.decisionId, decision.photonId.value, decision.toStage.name, decision.reason)
            }.toTypedArray(),
            *raw.atoms.map { it.atomId }.sorted().toTypedArray(),
            *raw.crystals.map { it.crystalId }.sorted().toTypedArray(),
            *derived.map { it.id.value }.toTypedArray(),
        )
        return raw.copy(derivedPhotons = derived, fingerprint = stableFingerprint)
    }

    private suspend fun saveIdempotent(photon: Photon) {
        val existing = photons.load(photon.id)
        if (existing == null) photons.save(photon)
        else check(existing == photon) { "Conflicting durable memory projection identity: ${photon.id.value}" }
    }
}

fun isLifeMemoryManagementPhoton(photon: Photon): Boolean =
    "life-memory-management" in photon.tags ||
        "memory-atom" in photon.tags ||
        "memory-crystal" in photon.tags

private fun parseFields(content: String): Map<String, String> {
    val fields = linkedMapOf<String, String>()
    content.lineSequence().filter { it.isNotBlank() }.forEach { line ->
        val separator = line.indexOf('=')
        require(separator > 0) { "Malformed durable life-memory record" }
        val key = line.substring(0, separator)
        val value = line.substring(separator + 1)
        require(fields.put(key, value) == null) { "Duplicate durable life-memory field: $key" }
    }
    return fields
}

private fun required(fields: Map<String, String>, key: String): String =
    fields[key] ?: throw IllegalArgumentException("Missing durable life-memory field: $key")

private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

private fun decode(value: String): String = String(
    Base64.getUrlDecoder().decode(value),
    StandardCharsets.UTF_8,
)

private fun number(value: Double?): String = value?.let(java.lang.Double::toHexString) ?: "~"
private fun optionalNumber(value: String): Double? = value.takeIf { it != "~" }?.let(java.lang.Double::valueOf)
