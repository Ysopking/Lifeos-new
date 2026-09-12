package app.lifeos.core.runtime.life

import app.lifeos.core.model.CanonicalPhotonState
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import app.lifeos.core.model.StableCognitiveIds
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Durable cursor authority keyed by authorized source id and adapter version. */
class PhotonBackedLifeSourceCheckpointStore(
    private val photons: PhotonRepository,
) {
    private val mutex = Mutex()

    suspend fun load(sourceId: String, adapterVersion: String): LifeSourceCursor? {
        require(sourceId.isNotBlank())
        require(adapterVersion.isNotBlank())
        val photon = photons.load(cursorPhotonId(sourceId, adapterVersion)) ?: return null
        val cursor = LifePersistenceCodec.decodeCursor(photon.content)
        require(cursor.sourceId == sourceId && cursor.adapterVersion == adapterVersion) {
            "Life source cursor identity/content mismatch"
        }
        return cursor
    }

    suspend fun save(cursor: LifeSourceCursor, recordedAt: Instant): LifeSourceCursor = mutex.withLock {
        val id = cursorPhotonId(cursor.sourceId, cursor.adapterVersion)
        val previous = photons.load(id)
        if (previous != null) {
            val persisted = LifePersistenceCodec.decodeCursor(previous.content)
            require(persisted.sourceId == cursor.sourceId && persisted.adapterVersion == cursor.adapterVersion) {
                "Life source cursor identity/content mismatch"
            }
            if (persisted.position == cursor.position) return@withLock persisted
        }
        val next = Photon(
            id = id,
            revision = (previous?.revision ?: 0L) + 1L,
            content = LifePersistenceCodec.encodeCursor(cursor),
            mimeType = CURSOR_MIME,
            phase = PhotonPhase.CONVERGED,
            semanticMass = 0.0,
            energy = 0.0,
            confidence = 1.0,
            provenance = Provenance(
                source = "life-source-checkpoint",
                actor = "lifeos",
                createdAt = recordedAt,
            ),
            tags = setOf(
                "life-memory-management",
                "life-source-cursor",
                "source:${cursor.sourceId}",
                "source-adapter:${cursor.adapterVersion}",
            ),
        )
        photons.save(next)
        cursor
    }

    companion object {
        const val CURSOR_MIME = "application/vnd.lifeos.life-source-cursor+text"

        fun cursorPhotonId(sourceId: String, adapterVersion: String): PhotonId = PhotonId(
            "life-source-cursor-" + StableCognitiveIds.fingerprint(
                "life-source-cursor/v1",
                sourceId,
                adapterVersion,
            )
        )
    }
}

/** Durable, rebuildable access metadata. Each source Photon gets one encrypted management Photon. */
class PhotonBackedMemoryAccessLedgerStore(
    private val photons: PhotonRepository,
) {
    private val mutex = Mutex()

    suspend fun loadLedger(): MemoryAccessLedger {
        val report = photons.loadReport()
        check(report.unreadableFiles.isEmpty()) {
            "Memory access ledger cannot be rebuilt with unreadable Photon files"
        }
        val profiles = report.photons
            .filter { it.mimeType == ACCESS_MIME && "memory-access-profile" in it.tags }
            .map { management ->
                val profile = LifePersistenceCodec.decodeAccessProfile(management.content)
                require(management.id == profilePhotonId(profile.photonId)) {
                    "Memory access profile identity/content mismatch"
                }
                profile
            }
            .associateBy { it.photonId }
        return MemoryAccessLedger(profiles)
    }

    suspend fun recordAccess(
        photonId: PhotonId,
        at: Instant,
        goalRelevance: Double? = null,
        relationshipWeight: Double? = null,
        futureRelevance: Double? = null,
        seinRelevance: Double? = null,
    ): MemoryUsageProfile = mutex.withLock {
        val id = profilePhotonId(photonId)
        val previousPhoton = photons.load(id)
        val previous = previousPhoton?.let { LifePersistenceCodec.decodeAccessProfile(it.content) }
            ?: MemoryUsageProfile(photonId = photonId, lastAccessAt = at)
        require(previous.photonId == photonId) { "Memory access profile target mismatch" }
        val next = MemoryAccessLedger(mapOf(photonId to previous)).recordAccess(
            photonId = photonId,
            at = at,
            goalRelevance = goalRelevance,
            relationshipWeight = relationshipWeight,
            futureRelevance = futureRelevance,
            seinRelevance = seinRelevance,
        ).profiles.getValue(photonId)
        val management = Photon(
            id = id,
            revision = (previousPhoton?.revision ?: 0L) + 1L,
            content = LifePersistenceCodec.encodeAccessProfile(next),
            mimeType = ACCESS_MIME,
            phase = PhotonPhase.CONVERGED,
            semanticMass = 0.0,
            energy = 0.0,
            confidence = 1.0,
            provenance = Provenance(
                source = "life-memory-access-ledger",
                actor = "lifeos",
                createdAt = at,
                parentIds = setOf(photonId),
            ),
            relations = setOf(PhotonRelation(photonId, RelationType.REFERENCES)),
            tags = setOf("life-memory-management", "memory-access-profile"),
        )
        photons.save(management)
        next
    }

    companion object {
        const val ACCESS_MIME = "application/vnd.lifeos.memory-access-profile+text"

        fun profilePhotonId(photonId: PhotonId): PhotonId = PhotonId(
            "memory-access-" + StableCognitiveIds.fingerprint(
                "memory-access-profile/v1",
                photonId.value,
            )
        )
    }
}

enum class LifeSourceGapReason {
    UNAUTHORIZED,
    UNAVAILABLE,
}

sealed interface DurableLifeIngestResult {
    data class Accepted(
        val batch: LifeIngestBatch,
        val persistedPhotonIds: List<PhotonId>,
    ) : DurableLifeIngestResult

    data class Rejected(
        val cursor: LifeSourceCursor,
        val gapPhoton: Photon,
    ) : DurableLifeIngestResult
}

/**
 * Crash-safe ingest ordering: immutable source-record Photons are persisted first and the cursor is
 * advanced last. Replaying after a crash is idempotent because each source record has a stable id.
 */
class DurableLifeIngestCoordinator(
    private val photons: PhotonRepository,
    private val checkpoints: PhotonBackedLifeSourceCheckpointStore = PhotonBackedLifeSourceCheckpointStore(photons),
    private val engine: ContinuousLifeIngestEngine = ContinuousLifeIngestEngine(),
    private val afterCommit: suspend (Instant) -> Unit = {},
) {
    suspend fun ingest(
        sourceId: String,
        adapterVersion: String,
        records: Collection<LifeSourceRecord>,
        nextPosition: String?,
        authorized: Boolean,
        observedAt: Instant,
    ): DurableLifeIngestResult {
        require(sourceId.isNotBlank())
        require(adapterVersion.isNotBlank())
        val cursor = checkpoints.load(sourceId, adapterVersion)
            ?: LifeSourceCursor(sourceId, null, adapterVersion)
        if (!authorized) {
            val gap = sourceGapPhoton(sourceId, adapterVersion, LifeSourceGapReason.UNAUTHORIZED, observedAt)
            saveImmutable(gap)
            return DurableLifeIngestResult.Rejected(cursor, gap)
        }
        require(records.all { it.sourceId == sourceId }) { "All records must belong to the authorized source" }
        validateDuplicateRecords(records)
        val contractBatch = engine.ingest(cursor, records, nextPosition, authorized = true)
        val evidence = records
            .distinctBy { it.recordId }
            .sortedWith(compareBy<LifeSourceRecord> { it.observedAt }.thenBy { it.recordId })
            .map { sourceRecordPhoton(it, adapterVersion) }
        evidence.forEach { saveImmutable(it) }
        val nextCursor = contractBatch.cursorAfter.copy(adapterVersion = adapterVersion)
        checkpoints.save(nextCursor, observedAt)
        afterCommit(observedAt)
        val stableBatch = contractBatch.copy(
            perception = PerceptionBatch(
                photons = evidence,
                batchFingerprint = StableCognitiveIds.fingerprint(
                    "durable-life-ingest-batch/v1",
                    sourceId,
                    adapterVersion,
                    *evidence.map { it.id.value }.toTypedArray(),
                ),
            )
        )
        return DurableLifeIngestResult.Accepted(stableBatch, evidence.map { it.id })
    }

    suspend fun recordUnavailable(
        sourceId: String,
        adapterVersion: String,
        observedAt: Instant,
    ): Photon {
        val gap = sourceGapPhoton(sourceId, adapterVersion, LifeSourceGapReason.UNAVAILABLE, observedAt)
        saveImmutable(gap)
        return gap
    }

    private suspend fun saveImmutable(photon: Photon) {
        val previous = photons.load(photon.id)
        if (previous == null) {
            photons.save(photon)
        } else {
            require(previous == photon) {
                "Immutable life source record identity collision: ${photon.id.value}"
            }
        }
    }

    private fun validateDuplicateRecords(records: Collection<LifeSourceRecord>) {
        records.groupBy { it.recordId }.forEach { (recordId, copies) ->
            require(copies.distinct().size == 1) {
                "Conflicting duplicate life source record: $recordId"
            }
        }
    }

    private fun sourceRecordPhoton(record: LifeSourceRecord, adapterVersion: String): Photon = Photon(
        id = PhotonId(
            "life-source-record-" + StableCognitiveIds.fingerprint(
                "life-source-record/v1",
                record.sourceId,
                adapterVersion,
                record.recordId,
            )
        ),
        content = record.payload,
        mimeType = record.mimeType,
        semanticMass = 0.5,
        energy = 0.5,
        confidence = record.confidence,
        provenance = Provenance(
            source = "life-source:${record.sourceId}",
            actor = record.sourceId,
            createdAt = record.observedAt,
        ),
        tags = record.tags + setOf(
            "perception",
            "life-ingest",
            "life-source-evidence",
            "source:${record.sourceId}",
            "source-record:${record.recordId}",
            "source-adapter:$adapterVersion",
        ),
    )

    private fun sourceGapPhoton(
        sourceId: String,
        adapterVersion: String,
        reason: LifeSourceGapReason,
        observedAt: Instant,
    ): Photon = Photon(
        id = PhotonId(
            "life-source-gap-" + StableCognitiveIds.fingerprint(
                "life-source-gap/v1",
                sourceId,
                adapterVersion,
                reason.name,
                observedAt.toString(),
            )
        ),
        content = "source=${LifePersistenceCodec.text(sourceId)}\nadapter=${LifePersistenceCodec.text(adapterVersion)}\nreason=${reason.name}",
        mimeType = "application/vnd.lifeos.life-source-gap+text",
        phase = PhotonPhase.CONVERGED,
        semanticMass = 0.2,
        energy = 0.0,
        confidence = 1.0,
        provenance = Provenance(
            source = "life-source-state",
            actor = "lifeos",
            createdAt = observedAt,
        ),
        tags = setOf(
            "life-source-gap",
            "permission-state",
            "source:$sourceId",
            "source-adapter:$adapterVersion",
            "source-gap:${reason.name.lowercase()}",
        ),
    )
}

data class DurableLifeMemoryState(
    val graph: LifeGraphSnapshot,
    val memory: LongTermMemoryProjection,
    val accessLedger: MemoryAccessLedger,
    val persistedDerivedPhotonIds: List<PhotonId>,
    val fingerprint: String,
)

/**
 * Productive long-term memory authority. Source evidence is read-only; compacted products are saved
 * as deterministic derived Photons in the same encrypted repository and excluded from future source
 * compaction passes.
 */
class DurableLifeMemoryRuntime(
    private val photons: PhotonRepository,
    private val accessStore: PhotonBackedMemoryAccessLedgerStore = PhotonBackedMemoryAccessLedgerStore(photons),
    private val graphProjector: LifeGraphProjector = LifeGraphProjector(),
    private val memoryEngine: LongTermMemoryEngine = LongTermMemoryEngine(),
) {
    @Volatile
    private var latest: DurableLifeMemoryState? = null

    fun current(): DurableLifeMemoryState? = latest

    suspend fun rehydrate(now: Instant): DurableLifeMemoryState = rebuild(now)

    suspend fun rebuild(now: Instant): DurableLifeMemoryState {
        val report = photons.loadReport()
        check(report.unreadableFiles.isEmpty()) {
            "Life memory cannot rebuild with unreadable Photon files"
        }
        val all = report.photons
        val sourceEvidence = all.filter(::isMemorySourceEvidence)
        val durableAccess = accessStore.loadLedger()
        val effectiveAccess = MemoryRelevanceProjection.enrich(durableAccess, sourceEvidence, all)
        val graph = graphProjector.project(sourceEvidence)
        val memory = memoryEngine.project(sourceEvidence, effectiveAccess, now)
        val stableDerived = DurableMemoryPhotonProjector.project(memory)
        stableDerived.forEach { saveDerived(it) }
        val state = DurableLifeMemoryState(
            graph = graph,
            memory = memory.copy(derivedPhotons = stableDerived),
            accessLedger = effectiveAccess,
            persistedDerivedPhotonIds = stableDerived.map { it.id },
            fingerprint = StableCognitiveIds.fingerprint(
                "durable-life-memory-state/v1",
                graph.fingerprint,
                memory.fingerprint,
                *stableDerived.map { it.id.value }.toTypedArray(),
            ),
        )
        latest = state
        return state
    }

    suspend fun recordAccess(
        photonId: PhotonId,
        at: Instant,
        goalRelevance: Double? = null,
        relationshipWeight: Double? = null,
        futureRelevance: Double? = null,
        seinRelevance: Double? = null,
    ): MemoryUsageProfile = accessStore.recordAccess(
        photonId,
        at,
        goalRelevance,
        relationshipWeight,
        futureRelevance,
        seinRelevance,
    )

    private suspend fun saveDerived(photon: Photon) {
        val previous = photons.load(photon.id)
        if (previous == null) {
            photons.save(photon)
        } else {
            require(previous == photon) {
                "Durable memory Photon identity collision: ${photon.id.value}"
            }
        }
    }

    private fun isMemorySourceEvidence(photon: Photon): Boolean =
        "life-memory-management" !in photon.tags &&
            "memory-atom" !in photon.tags &&
            "memory-crystal" !in photon.tags &&
            "life-source-gap" !in photon.tags &&
            !photon.mimeType.startsWith("application/vnd.lifeos.memory-")
}

private object MemoryRelevanceProjection {
    fun enrich(
        base: MemoryAccessLedger,
        sourceEvidence: List<Photon>,
        allPhotons: List<Photon>,
    ): MemoryAccessLedger {
        val sourceIds = sourceEvidence.mapTo(linkedSetOf()) { it.id }
        val byId = allPhotons.associateBy { it.id }
        val updates = base.profiles.toMutableMap()
        allPhotons.sortedBy { it.id.value }.forEach { signal ->
            val future = "future-evidence" in signal.tags || "future-planning-output" in signal.tags
            val goal = "goal" in signal.tags || signal.tags.any { it.startsWith("goal:") }
            val relationship = signal.tags.any { it.startsWith("relationship") || it.startsWith("entity-relationship:") }
            val sein = signal.tags.any { it.startsWith("sein") }
            if (!future && !goal && !relationship && !sein) return@forEach
            val roots = (signal.provenance.parentIds + signal.relations.map { it.target }).toSet()
            val ancestors = roots.flatMapTo(linkedSetOf()) { root ->
                sourceAncestors(root, sourceIds, byId)
            }
            ancestors.forEach { sourceId ->
                val source = byId[sourceId] ?: return@forEach
                val previous = updates[sourceId] ?: MemoryUsageProfile(sourceId, source.provenance.createdAt)
                updates[sourceId] = previous.copy(
                    goalRelevance = maxOf(previous.goalRelevance, if (goal) signal.confidence else 0.0),
                    relationshipWeight = maxOf(previous.relationshipWeight, if (relationship) signal.confidence else 0.0),
                    futureRelevance = maxOf(previous.futureRelevance, if (future) signal.confidence else 0.0),
                    seinRelevance = maxOf(previous.seinRelevance, if (sein) signal.confidence else 0.0),
                )
            }
        }
        return MemoryAccessLedger(updates)
    }

    private fun sourceAncestors(
        start: PhotonId,
        sourceIds: Set<PhotonId>,
        byId: Map<PhotonId, Photon>,
    ): Set<PhotonId> {
        val result = linkedSetOf<PhotonId>()
        val queue = ArrayDeque<Pair<PhotonId, Int>>()
        val visited = linkedSetOf<PhotonId>()
        queue += start to 0
        while (queue.isNotEmpty()) {
            val (id, depth) = queue.removeFirst()
            if (!visited.add(id) || depth > MAX_LINEAGE_DEPTH) continue
            if (id in sourceIds) result += id
            val photon = byId[id] ?: continue
            (photon.provenance.parentIds + photon.relations.map { it.target })
                .sortedBy { it.value }
                .forEach { queue += it to (depth + 1) }
        }
        return result
    }

    private const val MAX_LINEAGE_DEPTH = 16
}

private object DurableMemoryPhotonProjector {
    fun project(memory: LongTermMemoryProjection): List<Photon> {
        val atomIds = memory.atoms.associate { atom -> atom.atomId to atomPhotonId(atom) }
        val atomPhotons = memory.atoms.map { atom ->
            Photon(
                id = atomIds.getValue(atom.atomId),
                content = atom.content,
                mimeType = "application/vnd.lifeos.memory-atom+text",
                phase = PhotonPhase.CONVERGED,
                semanticMass = 0.35,
                energy = 0.1,
                confidence = atom.confidence,
                provenance = Provenance(
                    source = "lifeos-long-term-memory",
                    actor = "lifeos",
                    createdAt = atom.observedAt,
                    parentIds = atom.sourcePhotonIds,
                ),
                relations = atom.sourcePhotonIds.map { PhotonRelation(it, RelationType.DERIVED_FROM) }.toSet(),
                tags = setOf(
                    "memory-atom",
                    "memory-kind:${atom.kind.name.lowercase()}",
                    "memory-stage:${atom.stage.name.lowercase()}",
                    "memory-episode:${atom.episodeId}",
                    "memory-producer:${atom.producerVersion}",
                    "source-state-hash:${atom.sourceStateHash}",
                ),
            )
        }
        val atomsByEpisode = memory.atoms.groupBy { it.episodeId }
        val crystalPhotons = memory.crystals.map { crystal ->
            val stableAtomIds = crystal.atomIds.mapNotNull(atomIds::get).toSortedSet(compareBy { it.value })
            val stateHashes = atomsByEpisode.values.flatten()
                .filter { it.atomId in crystal.atomIds }
                .map { it.sourceStateHash }
                .toSortedSet()
            Photon(
                id = crystalPhotonId(crystal, stableAtomIds),
                content = crystal.semanticCore,
                mimeType = "application/vnd.lifeos.memory-crystal+text",
                phase = PhotonPhase.CONVERGED,
                semanticMass = 0.7,
                energy = 0.05,
                confidence = crystal.confidence,
                provenance = Provenance(
                    source = "lifeos-long-term-memory",
                    actor = "lifeos",
                    createdAt = crystal.endedAt,
                    parentIds = crystal.sourcePhotonIds + stableAtomIds,
                ),
                relations = crystal.sourcePhotonIds.map { PhotonRelation(it, RelationType.DERIVED_FROM) }.toSet() +
                    stableAtomIds.map { PhotonRelation(it, RelationType.REFERENCES) },
                tags = buildSet {
                    add("memory-crystal")
                    add("memory-stage:crystallized")
                    add("memory-producer:${crystal.producerVersion}")
                    stateHashes.forEach { add("source-state-hash:$it") }
                },
            )
        }
        return (atomPhotons + crystalPhotons).sortedBy { it.id.value }
    }

    private fun atomPhotonId(atom: MemoryAtom): PhotonId = PhotonId(
        "memory-atom-" + StableCognitiveIds.fingerprint(
            "durable-memory-atom/v1",
            atom.atomId,
            atom.stage.name,
            atom.producerVersion,
            atom.sourceStateHash,
        )
    )

    private fun crystalPhotonId(crystal: MemoryCrystal, atomIds: Set<PhotonId>): PhotonId = PhotonId(
        "memory-crystal-" + StableCognitiveIds.fingerprint(
            "durable-memory-crystal/v1",
            crystal.crystalId,
            crystal.producerVersion,
            *atomIds.map { it.value }.toTypedArray(),
        )
    )
}

private object LifePersistenceCodec {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun text(value: String): String = encoder.encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    private fun raw(value: String): String = String(decoder.decode(value), StandardCharsets.UTF_8)

    fun encodeCursor(cursor: LifeSourceCursor): String = buildString {
        appendLine("schema=life-source-cursor/v1")
        appendLine("source=${text(cursor.sourceId)}")
        appendLine("adapter=${text(cursor.adapterVersion)}")
        appendLine("position_present=${if (cursor.position == null) 0 else 1}")
        append("position=${text(cursor.position.orEmpty())}")
    }

    fun decodeCursor(content: String): LifeSourceCursor {
        val values = fields(content)
        require(values["schema"] == "life-source-cursor/v1") { "Unsupported life source cursor schema" }
        val present = values.getValue("position_present")
        require(present == "0" || present == "1")
        return LifeSourceCursor(
            sourceId = raw(values.getValue("source")),
            position = if (present == "1") raw(values.getValue("position")) else null,
            adapterVersion = raw(values.getValue("adapter")),
        )
    }

    fun encodeAccessProfile(profile: MemoryUsageProfile): String = buildString {
        appendLine("schema=memory-access-profile/v1")
        appendLine("photon=${text(profile.photonId.value)}")
        appendLine("last_access=${profile.lastAccessAt}")
        appendLine("access_count=${profile.accessCount}")
        appendLine("goal=${java.lang.Double.toHexString(profile.goalRelevance)}")
        appendLine("relationship=${java.lang.Double.toHexString(profile.relationshipWeight)}")
        appendLine("future=${java.lang.Double.toHexString(profile.futureRelevance)}")
        append("sein=${java.lang.Double.toHexString(profile.seinRelevance)}")
    }

    fun decodeAccessProfile(content: String): MemoryUsageProfile {
        val values = fields(content)
        require(values["schema"] == "memory-access-profile/v1") { "Unsupported memory access schema" }
        return MemoryUsageProfile(
            photonId = PhotonId(raw(values.getValue("photon"))),
            lastAccessAt = Instant.parse(values.getValue("last_access")),
            accessCount = values.getValue("access_count").toLong().also { require(it >= 0L) },
            goalRelevance = java.lang.Double.valueOf(values.getValue("goal")),
            relationshipWeight = java.lang.Double.valueOf(values.getValue("relationship")),
            futureRelevance = java.lang.Double.valueOf(values.getValue("future")),
            seinRelevance = java.lang.Double.valueOf(values.getValue("sein")),
        )
    }

    private fun fields(content: String): Map<String, String> {
        require(content.length <= MAX_CONTENT_CHARS) { "Life management payload too large" }
        val lines = content.lineSequence().toList()
        require(lines.size in 2..MAX_FIELDS) { "Invalid life management field count" }
        return lines.associate { line ->
            require(line.length <= MAX_FIELD_CHARS) { "Life management field too large" }
            val key = line.substringBefore('=', missingDelimiterValue = "").trim()
            val value = line.substringAfter('=', missingDelimiterValue = "")
            require(key.isNotBlank()) { "Invalid life management field" }
            key to value
        }.also { require(it.size == lines.size) { "Duplicate life management fields" } }
    }

    private const val MAX_CONTENT_CHARS = 64 * 1024
    private const val MAX_FIELDS = 32
    private const val MAX_FIELD_CHARS = 16 * 1024
}
