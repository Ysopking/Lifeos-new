package app.lifeos.core.runtime

import app.lifeos.core.runtime.cognition.CognitiveEventJournal
import app.lifeos.core.runtime.world.WorldFormulaSnapshot
import app.lifeos.core.runtime.world.WorldFormulaSnapshotCodec
import app.lifeos.core.runtime.world.WorldFormulaSnapshotRepository
import java.security.MessageDigest

data class CognitiveSnapshot(
    val schemaVersion: Int,
    val worldRevision: Long,
    val eventSequence: Long,
    val worldRoot: String,
    val payload: ByteArray,
    val projectionVersion: Int = 1,
    val dependencyIndexFingerprint: String = "uninitialized",
    val memoryIndexFingerprint: String = "uninitialized",
) {
    init {
        require(schemaVersion > 0)
        require(projectionVersion > 0)
        require(worldRevision >= 0)
        require(eventSequence >= 0)
        require(worldRoot.isNotBlank())
        require(dependencyIndexFingerprint.isNotBlank())
        require(memoryIndexFingerprint.isNotBlank())
    }
}

data class SnapshotManifest(
    val schemaVersion: Int,
    val worldRevision: Long,
    val eventSequence: Long,
    val worldRoot: String,
    val payloadSha256: String,
    val projectionVersion: Int = 1,
    val dependencyIndexFingerprint: String = "uninitialized",
    val memoryIndexFingerprint: String = "uninitialized",
)

class SnapshotVerifier {
    fun manifest(snapshot: CognitiveSnapshot): SnapshotManifest = SnapshotManifest(
        schemaVersion = snapshot.schemaVersion,
        projectionVersion = snapshot.projectionVersion,
        worldRevision = snapshot.worldRevision,
        eventSequence = snapshot.eventSequence,
        worldRoot = snapshot.worldRoot,
        dependencyIndexFingerprint = snapshot.dependencyIndexFingerprint,
        memoryIndexFingerprint = snapshot.memoryIndexFingerprint,
        payloadSha256 = sha256(snapshot.payload),
    )

    fun verify(snapshot: CognitiveSnapshot, manifest: SnapshotManifest): Boolean =
        snapshot.schemaVersion == manifest.schemaVersion &&
            snapshot.projectionVersion == manifest.projectionVersion &&
            snapshot.worldRevision == manifest.worldRevision &&
            snapshot.eventSequence == manifest.eventSequence &&
            snapshot.worldRoot == manifest.worldRoot &&
            snapshot.dependencyIndexFingerprint == manifest.dependencyIndexFingerprint &&
            snapshot.memoryIndexFingerprint == manifest.memoryIndexFingerprint &&
            sha256(snapshot.payload) == manifest.payloadSha256 &&
            worldPayloadMatchesRoot(snapshot)

    private fun worldPayloadMatchesRoot(snapshot: CognitiveSnapshot): Boolean {
        if (snapshot.schemaVersion < WORLD_PAYLOAD_SCHEMA_VERSION) return true
        return runCatching {
            WorldFormulaSnapshotCodec.decode(snapshot.payload).id == snapshot.worldRoot
        }.getOrDefault(false)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val WORLD_PAYLOAD_SCHEMA_VERSION = 2
    }
}

/** Rehydration starts from the latest verified snapshot and replays only its event tail. */
data class SnapshotReplayPlan(val snapshot: CognitiveSnapshot?, val eventsAfterSequence: Long)

class SnapshotCompactor(private val verifier: SnapshotVerifier = SnapshotVerifier()) {
    fun selectLatestVerified(
        candidates: Collection<Pair<CognitiveSnapshot, SnapshotManifest>>,
    ): SnapshotReplayPlan {
        val latest = candidates.asSequence()
            .filter { (snapshot, manifest) -> verifier.verify(snapshot, manifest) }
            .maxWithOrNull(compareBy<Pair<CognitiveSnapshot, SnapshotManifest>>({ it.first.eventSequence }, { it.first.worldRevision }))
            ?.first
        return SnapshotReplayPlan(latest, latest?.eventSequence ?: 0L)
    }
}


interface CognitiveSnapshotRepository {
    suspend fun loadAll(): List<Pair<CognitiveSnapshot, SnapshotManifest>>
    suspend fun save(snapshot: CognitiveSnapshot, manifest: SnapshotManifest)
}

interface CognitiveSnapshotCodec {
    fun encode(snapshot: CognitiveSnapshot, manifest: SnapshotManifest): ByteArray
    fun decode(bytes: ByteArray): Pair<CognitiveSnapshot, SnapshotManifest>
}

data class CognitiveSnapshotReplay(
    val snapshot: CognitiveSnapshot?,
    val tail: List<app.lifeos.core.runtime.cognition.JournalEntry>,
)

class CognitiveSnapshotManager(
    private val repository: CognitiveSnapshotRepository,
    private val verifier: SnapshotVerifier = SnapshotVerifier(),
    private val compactor: SnapshotCompactor = SnapshotCompactor(verifier),
) {
    @Volatile
    private var lastReplay: CognitiveSnapshotReplay? = null

    suspend fun latestVerified(): CognitiveSnapshot? =
        compactor.selectLatestVerified(repository.loadAll()).snapshot

    suspend fun persist(snapshot: CognitiveSnapshot) {
        val manifest = verifier.manifest(snapshot)
        repository.save(snapshot, manifest)
    }

    suspend fun replay(
        journal: app.lifeos.core.runtime.cognition.CognitiveEventJournal,
        tailLimit: Int = 4096,
    ): CognitiveSnapshotReplay {
        require(tailLimit > 0)
        val head = journal.size()
        val candidates = repository.loadAll()
            .filter { (snapshot, _) -> snapshot.eventSequence <= head }
        val plan = compactor.selectLatestVerified(candidates)
        var cursor = plan.eventsAfterSequence
        val tail = mutableListOf<app.lifeos.core.runtime.cognition.JournalEntry>()

        while (cursor < head) {
            val remaining = (head - cursor).coerceAtMost(tailLimit.toLong()).toInt()
            val batch = journal.readFrom(cursor, remaining)
            check(batch.isNotEmpty()) {
                "Cognitive snapshot replay stalled before journal head: cursor=$cursor head=$head"
            }
            var expected = cursor + 1L
            batch.forEach { entry ->
                check(entry.offset == expected) {
                    "Cognitive snapshot replay gap: expected=$expected actual=${entry.offset}"
                }
                expected += 1L
            }
            tail += batch
            cursor = batch.last().offset
        }

        check(cursor == head) {
            "Cognitive snapshot replay did not reach frozen journal head: cursor=$cursor head=$head"
        }
        return CognitiveSnapshotReplay(plan.snapshot, tail).also { lastReplay = it }
    }

    fun cachedReplay(): CognitiveSnapshotReplay? = lastReplay
}

data class CognitiveSnapshotDependencyState(
    val revision: Long,
    val fingerprint: String,
) {
    init {
        require(revision >= 0L)
        require(fingerprint.isNotBlank())
    }
}

/**
 * Materializes a reconstructible cognitive checkpoint from already-authoritative durable stores.
 * WorldFormulaSnapshot remains the world payload authority; this snapshot only binds it atomically
 * to the journal head plus dependency/memory fingerprints.
 */
class CognitiveSnapshotProducer(
    private val manager: CognitiveSnapshotManager,
    private val journal: CognitiveEventJournal,
    private val worlds: WorldFormulaSnapshotRepository,
    private val activeWorldSnapshotId: suspend () -> String?,
    private val dependencyState: suspend () -> CognitiveSnapshotDependencyState,
    private val memoryFingerprint: () -> String?,
) {
    suspend fun captureLatest(): CognitiveSnapshot? {
        val snapshotId = activeWorldSnapshotId()?.takeIf { it.isNotBlank() } ?: return null
        val world = requireNotNull(worlds.load(snapshotId)) {
            "Productive world head points to a missing WorldFormula snapshot: $snapshotId"
        }
        return capture(world)
    }

    suspend fun capture(world: WorldFormulaSnapshot): CognitiveSnapshot? {
        val memory = memoryFingerprint()?.takeIf { it.isNotBlank() } ?: return null
        val dependency = dependencyState()
        val snapshot = CognitiveSnapshot(
            schemaVersion = WORLD_PAYLOAD_SCHEMA_VERSION,
            projectionVersion = WORLD_PAYLOAD_PROJECTION_VERSION,
            worldRevision = dependency.revision,
            eventSequence = journal.size(),
            worldRoot = world.id,
            payload = WorldFormulaSnapshotCodec.encode(world),
            dependencyIndexFingerprint = dependency.fingerprint,
            memoryIndexFingerprint = memory,
        )
        manager.persist(snapshot)
        return snapshot
    }

    private companion object {
        const val WORLD_PAYLOAD_SCHEMA_VERSION = 2
        const val WORLD_PAYLOAD_PROJECTION_VERSION = 2
    }
}

/** Process seam for persisted WorldFormula snapshots and explicit boot-time capture. */
object CognitiveSnapshotRuntimeRegistry {
    @Volatile
    private var installed: CognitiveSnapshotProducer? = null

    fun install(producer: CognitiveSnapshotProducer) {
        installed = producer
    }

    suspend fun capturePersistedWorld(world: WorldFormulaSnapshot): CognitiveSnapshot? =
        installed?.capture(world)

    suspend fun captureLatest(): CognitiveSnapshot? =
        installed?.captureLatest()
}
