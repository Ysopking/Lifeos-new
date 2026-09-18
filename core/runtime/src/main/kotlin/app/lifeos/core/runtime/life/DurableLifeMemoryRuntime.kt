package app.lifeos.core.runtime.life

import app.lifeos.core.model.CanonicalPhotonState
import app.lifeos.core.model.GraphActivityClass
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.StableCognitiveIds
import app.lifeos.core.runtime.CognitiveMemoryEntry
import app.lifeos.core.runtime.CognitiveMemoryFabric
import app.lifeos.core.runtime.CognitiveMemoryLayout
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

    val cursor: LifeSourceCursor
        get() = LifeSourceCursor(descriptor.sourceId, position, descriptor.adapterVersion)
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
    val memoryLayout: CognitiveMemoryLayout = CognitiveMemoryLayout.empty(),
)

/** Photon-backed source cursor. Evidence is committed before this management Photon advances. */
class PhotonBackedLifeSourceCheckpointStore(
    private val photons: PhotonRepository,
) {
    suspend fun load(descriptor: LifeSourceDescriptor): DurableLifeSourceCheckpoint {
        val photon = photons.load(checkpointId(descriptor))
            ?: return DurableLifeSourceCheckpoint(descriptor, null, null, 0L)
        return decodeCheckpoint(descriptor, photon)
    }

    suspend fun commit(
        previous: DurableLifeSourceCheckpoint,
        nextPosition: String?,
        batchFingerprint: String,
        evidenceIds: Set<PhotonId>,
        committedAt: Instant,
    ): DurableLifeSourceCheckpoint {
        require(batchFingerprint.isNotBlank())
        val current = load(previous.descriptor)
        if (current.position == nextPosition && current.lastBatchFingerprint == batchFingerprint) return current
        require(current.revision == previous.revision) {
            "Life source checkpoint changed concurrently; refusing stale cursor advance"
        }
        val desired = DurableLifeSourceCheckpoint(
            descriptor = previous.descriptor,
            position = nextPosition,
            lastBatchFingerprint = batchFingerprint,
            revision = previous.revision + 1L,
        )
        photons.save(
            Photon(
                id = checkpointId(previous.descriptor),
                revision = desired.revision,
                content = encodeCheckpoint(desired),
                mimeType = MIME_TYPE,
                semanticMass = 0.0,
                energy = 0.0,
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

    private fun decodeCheckpoint(
        descriptor: LifeSourceDescriptor,
        photon: Photon,
    ): DurableLifeSourceCheckpoint {
        require("life-source-checkpoint" in photon.tags) { "Life source checkpoint tag missing" }
        val fields = parseFields(photon.content)
        require(fields["schema"] == SCHEMA) { "Unsupported life source checkpoint schema" }
        require(decode(required(fields, "source")) == descriptor.sourceId) {
            "Life source checkpoint source mismatch"
        }
        require(decode(required(fields, "adapter")) == descriptor.adapterVersion) {
            "Life source checkpoint adapter mismatch"
        }
        return DurableLifeSourceCheckpoint(
            descriptor = descriptor,
            position = required(fields, "position").takeIf { it != NULL }?.let(::decode),
            lastBatchFingerprint = required(fields, "batch").takeIf { it != NULL },
            revision = photon.revision,
        )
    }

    private fun encodeCheckpoint(checkpoint: DurableLifeSourceCheckpoint): String = buildString {
        appendLine("schema=$SCHEMA")
        appendLine("source=${encode(checkpoint.descriptor.sourceId)}")
        appendLine("adapter=${encode(checkpoint.descriptor.adapterVersion)}")
        appendLine("position=${checkpoint.position?.let(::encode) ?: NULL}")
        append("batch=${checkpoint.lastBatchFingerprint ?: NULL}")
    }

    private fun checkpointId(descriptor: LifeSourceDescriptor): PhotonId = PhotonId(
        "life-source-checkpoint-" + StableCognitiveIds.fingerprint(
            "life-source-checkpoint-id/v2",
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
        val event = Photon(
            id = PhotonId(
                "memory-access-event-" + StableCognitiveIds.fingerprint(
                    "memory-access-event/v2",
                    photonId.value,
                    accessKey,
                )
            ),
            content = buildString {
                appendLine("schema=1")
                appendLine("target=${encode(photonId.value)}")
                appendLine("access_key=${encode(accessKey)}")
                appendLine("at=$at")
                appendLine("goal=${number(goalRelevance)}")
                appendLine("relationship=${number(relationshipWeight)}")
                appendLine("future=${number(futureRelevance)}")
                append("sein=${number(seinRelevance)}")
            },
            mimeType = "application/vnd.lifeos.memory-access-event+text",
            semanticMass = 0.0,
            energy = 0.0,
            confidence = 1.0,
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

    suspend fun snapshot(): MemoryAccessLedger = snapshot(photons.loadAll())

    fun snapshot(allPhotons: Collection<Photon>): MemoryAccessLedger {
        val events = allPhotons
            .filter { "memory-access-event" in it.tags }
            .map(::decodeEvent)
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

    private fun decodeEvent(photon: Photon): AccessEvent {
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
            val gap = sourceGap(descriptor, before.cursor, committedAt, "UNAUTHORIZED")
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
        require(records.all { it.sourceId == descriptor.sourceId }) {
            "Life source record belongs to another source"
        }
        val canonicalRecords = records.groupBy { it.recordId }.map { (recordId, duplicates) ->
            require(duplicates.distinct().size == 1) { "Conflicting duplicate life source record: $recordId" }
            duplicates.single()
        }.sortedWith(compareBy<LifeSourceRecord> { it.observedAt }.thenBy { it.recordId })
        val evidence = canonicalRecords.map { record -> sourceRecordPhoton(descriptor, record) }
        evidence.forEach { saveIdempotent(it) }
        val batchFingerprint = StableCognitiveIds.fingerprint(
            "life-source-ingest-batch/v2",
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

    suspend fun recordUnavailable(
        descriptor: LifeSourceDescriptor,
        observedAt: Instant,
    ): Photon {
        val checkpoint = checkpoints.load(descriptor)
        val gap = sourceGap(descriptor, checkpoint.cursor, observedAt, "UNAVAILABLE")
        saveIdempotent(gap)
        return gap
    }

    private fun sourceRecordPhoton(descriptor: LifeSourceDescriptor, record: LifeSourceRecord): Photon = Photon(
        id = PhotonId(
            "life-source-record-" + StableCognitiveIds.fingerprint(
                "life-source-record-id/v2",
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
            "perception",
            "life-ingest",
            "life-source-evidence",
            "source:${descriptor.sourceId}",
            "source-adapter:${descriptor.adapterVersion}",
            "source-record:${record.recordId}",
        ),
    )

    private fun sourceGap(
        descriptor: LifeSourceDescriptor,
        cursor: LifeSourceCursor,
        observedAt: Instant,
        state: String,
    ): Photon = Photon(
        id = PhotonId(
            "life-source-gap-" + StableCognitiveIds.fingerprint(
                "life-source-gap/v2",
                descriptor.sourceId,
                descriptor.adapterVersion,
                cursor.position.orEmpty(),
                state,
                observedAt.toString(),
            )
        ),
        content = buildString {
            appendLine("source=${encode(descriptor.sourceId)}")
            appendLine("adapter=${encode(descriptor.adapterVersion)}")
            appendLine("cursor=${encode(cursor.position.orEmpty())}")
            append("state=$state")
        },
        mimeType = "application/vnd.lifeos.source-gap+text",
        semanticMass = 0.4,
        energy = 0.1,
        confidence = 1.0,
        provenance = Provenance(
            source = "life-source-state",
            actor = descriptor.sourceId,
            createdAt = observedAt,
        ),
        tags = setOf(
            "life-source-gap",
            "permission-state:${state.lowercase()}",
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
                "Conflicting durable life source identity: ${photon.id.value}; " +
                    "source record changed without adapter/version identity change"
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
    private val maxResidentPhotons: Int = DEFAULT_MAX_RESIDENT_PHOTONS,
) {
    private val memoryFabric = CognitiveMemoryFabric(photons)

    init {
        require(maxResidentPhotons > 0)
    }
    @Volatile
    private var latest: DurableLifeMemorySnapshot? = null

    fun current(): DurableLifeMemorySnapshot? = latest

    fun memoryLayout(): CognitiveMemoryLayout = memoryFabric.layout()

    fun hotContext(limit: Int = DEFAULT_HOT_CONTEXT_LIMIT): List<Photon> =
        memoryFabric.hotContext(limit)

    suspend fun hydrate(ref: PhotonRevisionRef): Photon? = memoryFabric.hydrate(ref)

    suspend fun rebuild(now: Instant): DurableLifeMemorySnapshot {
        val all = photons.loadAll()
        val storedAuthoritative = all.filterNot(::isLifeMemoryManagementPhoton)
        val authoritative = ActiveLifeSourceProjection.filter(storedAuthoritative, all)
        val graphEvidence = authoritative.filterNot { "causal-ledger" in it.tags }
        val memoryEvidence = graphEvidence.filterNot { "life-source-gap" in it.tags }
        val durableAccess = accessStore.snapshot(all)
        val effectiveAccess = rebuildableRelevance(durableAccess, memoryEvidence, all)
        val graph = graphProjector.project(graphEvidence)
        val rawMemory = memoryEngine.project(memoryEvidence, effectiveAccess, now)
        val memory = stabilize(rawMemory, memoryEvidence)
        for (derived in memory.derivedPhotons) {
            saveIdempotent(derived)
        }

        val decisionsById = memory.decisions.associateBy { it.photonId }
        val memoryLayout = memoryFabric.rebuild(
            entries = memoryEvidence.map { photon ->
                val stage = decisionsById[photon.id]?.toStage ?: MemoryStage.HOT
                val profile = effectiveAccess.profiles[photon.id]
                CognitiveMemoryEntry(
                    ref = PhotonRevisionRef(photon.id, photon.revision),
                    activity = when (stage) {
                        MemoryStage.HOT -> GraphActivityClass.HOT
                        MemoryStage.WARM -> GraphActivityClass.WARM
                        MemoryStage.COLD,
                        MemoryStage.CRYSTALLIZED -> GraphActivityClass.COLD
                    },
                    lastAccessRevision = saturatingAccessRevision(
                        photonRevision = photon.revision,
                        accessCount = profile?.accessCount ?: 0L,
                    ),
                    activeMatter = photon.tags.any {
                        it == "matter" || it.startsWith("matter:") || it.startsWith("life-matter:")
                    },
                    activeConversation = photon.tags.any {
                        it == "chat" || it.startsWith("conversation:")
                    },
                )
            },
            availablePhotons = memoryEvidence,
            maxResidentPhotons = maxResidentPhotons,
        )

        val snapshot = DurableLifeMemorySnapshot(
            graph = graph,
            memory = memory,
            accessLedger = effectiveAccess,
            authoritativePhotonCount = authoritative.size,
            fingerprint = StableCognitiveIds.fingerprint(
                "durable-life-memory-snapshot/v3",
                graph.fingerprint,
                memory.fingerprint,
                *effectiveAccess.profiles.entries.sortedBy { it.key.value }.flatMap { (id, profile) ->
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
            ),
            memoryLayout = memoryLayout,
        )
        latest = snapshot
        return snapshot
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

    suspend fun recordUnavailable(
        descriptor: LifeSourceDescriptor,
        observedAt: Instant,
    ): Pair<Photon, DurableLifeMemorySnapshot> {
        val gap = ingestor.recordUnavailable(descriptor, observedAt)
        return gap to rebuild(observedAt)
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

    private fun saturatingAccessRevision(
        photonRevision: Long,
        accessCount: Long,
    ): Long {
        require(photonRevision > 0L)
        require(accessCount >= 0L)
        return if (Long.MAX_VALUE - photonRevision < accessCount) {
            Long.MAX_VALUE
        } else {
            photonRevision + accessCount
        }
    }

    private fun stabilize(
        raw: LongTermMemoryProjection,
        authoritative: List<Photon>,
    ): LongTermMemoryProjection {
        val sources = authoritative.associateBy { it.id }
        val idMap = raw.derivedPhotons.associate { photon ->
            photon.id to stableDerivedId(photon)
        }
        val derived = raw.derivedPhotons.map { photon ->
            val sourceParents = photon.provenance.parentIds.mapNotNull(sources::get)
            val deterministicCreatedAt = when {
                sourceParents.isEmpty() -> photon.provenance.createdAt
                "memory-crystal" in photon.tags -> sourceParents.maxOf { it.provenance.createdAt }
                else -> sourceParents.minOf { it.provenance.createdAt }
            }
            val stableParents = photon.provenance.parentIds.mapTo(linkedSetOf()) { idMap[it] ?: it }
            val stableRelations = photon.relations.mapTo(linkedSetOf()) { relation ->
                PhotonRelation(
                    target = idMap[relation.target] ?: relation.target,
                    type = relation.type,
                    weight = relation.weight,
                )
            }
            val stateTags = sourceParents.mapTo(linkedSetOf()) { source ->
                "source-state:${CanonicalPhotonState.inputHash(source).value}"
            }
            photon.copy(
                id = idMap.getValue(photon.id),
                provenance = photon.provenance.copy(
                    createdAt = deterministicCreatedAt,
                    parentIds = stableParents,
                ),
                relations = stableRelations,
                tags = photon.tags + stateTags + setOf(
                    "life-memory-management",
                    "producer-version:${LongTermMemoryEngine.RUNTIME_VERSION}",
                ),
            )
        }.sortedBy { it.id.value }
        val stableFingerprint = StableCognitiveIds.fingerprint(
            "long-term-memory-durable-projection/v2",
            *raw.decisions.sortedBy { it.photonId.value }.flatMap { decision ->
                listOf(decision.decisionId, decision.photonId.value, decision.toStage.name, decision.reason)
            }.toTypedArray(),
            *raw.atoms.map { it.atomId }.sorted().toTypedArray(),
            *raw.crystals.map { it.crystalId }.sorted().toTypedArray(),
            *derived.map { it.id.value }.toTypedArray(),
        )
        return raw.copy(derivedPhotons = derived, fingerprint = stableFingerprint)
    }

    private fun stableDerivedId(photon: Photon): PhotonId {
        val prefix = if ("memory-crystal" in photon.tags) "memory-crystal-" else "memory-atom-"
        return PhotonId(
            prefix + StableCognitiveIds.fingerprint(
                "durable-memory-photon/v2",
                photon.id.value,
                LongTermMemoryEngine.RUNTIME_VERSION,
                *photon.tags.sorted().toTypedArray(),
            )
        )
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

/** Goal/future/relationship/Sein relevance can be rebuilt from durable lineage after restart. */
private fun rebuildableRelevance(
    base: MemoryAccessLedger,
    sourceEvidence: List<Photon>,
    allPhotons: List<Photon>,
): MemoryAccessLedger {
    val sourceIds = sourceEvidence.mapTo(linkedSetOf()) { it.id }
    val byId = allPhotons.associateBy { it.id }
    val profiles = base.profiles.toMutableMap()
    allPhotons.sortedBy { it.id.value }.forEach { signal ->
        val future = "future-evidence" in signal.tags || "future-planning-output" in signal.tags
        val goal = "goal" in signal.tags || signal.tags.any { it.startsWith("goal:") }
        val relationship = signal.tags.any {
            it.startsWith("relationship") || it.startsWith("entity-relationship:")
        }
        val sein = signal.tags.any { it.startsWith("sein") }
        if (!future && !goal && !relationship && !sein) return@forEach
        val roots = (signal.provenance.parentIds + signal.relations.map { it.target }).toSet()
        roots.flatMapTo(linkedSetOf()) { root ->
            sourceAncestors(root, sourceIds, byId)
        }.forEach { sourceId ->
            val source = byId[sourceId] ?: return@forEach
            val previous = profiles[sourceId] ?: MemoryUsageProfile(sourceId, source.provenance.createdAt)
            profiles[sourceId] = previous.copy(
                goalRelevance = maxOf(previous.goalRelevance, if (goal) signal.confidence else 0.0),
                relationshipWeight = maxOf(
                    previous.relationshipWeight,
                    if (relationship) signal.confidence else 0.0,
                ),
                futureRelevance = maxOf(previous.futureRelevance, if (future) signal.confidence else 0.0),
                seinRelevance = maxOf(previous.seinRelevance, if (sein) signal.confidence else 0.0),
            )
        }
    }
    return MemoryAccessLedger(profiles)
}

private fun sourceAncestors(
    start: PhotonId,
    sourceIds: Set<PhotonId>,
    byId: Map<PhotonId, Photon>,
): Set<PhotonId> {
    val result = linkedSetOf<PhotonId>()
    val queue = ArrayDeque<Pair<PhotonId, Int>>()
    val visited = linkedSetOf<PhotonId>()
    queue.addLast(start to 0)
    while (queue.isNotEmpty()) {
        val (id, depth) = queue.removeFirst()
        if (!visited.add(id) || depth > MAX_LINEAGE_DEPTH) continue
        if (id in sourceIds) result += id
        val photon = byId[id] ?: continue
        (photon.provenance.parentIds + photon.relations.map { it.target })
            .sortedBy { it.value }
            .forEach { queue.addLast(it to (depth + 1)) }
    }
    return result
}

private fun parseFields(content: String): Map<String, String> {
    require(content.length <= MAX_MANAGEMENT_CONTENT_CHARS) { "Durable life-memory record too large" }
    val fields = linkedMapOf<String, String>()
    val lines = content.lineSequence().filter { it.isNotBlank() }.toList()
    require(lines.size <= MAX_MANAGEMENT_FIELDS) { "Too many durable life-memory fields" }
    lines.forEach { line ->
        require(line.length <= MAX_MANAGEMENT_FIELD_CHARS) { "Durable life-memory field too large" }
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

private const val DEFAULT_MAX_RESIDENT_PHOTONS = 4_096
private const val DEFAULT_HOT_CONTEXT_LIMIT = 256
private const val MAX_LINEAGE_DEPTH = 16
private const val MAX_MANAGEMENT_CONTENT_CHARS = 64 * 1024
private const val MAX_MANAGEMENT_FIELDS = 32
private const val MAX_MANAGEMENT_FIELD_CHARS = 16 * 1024
