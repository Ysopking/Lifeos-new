package app.lifeos.core.runtime.life

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.Provenance
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DurableLifeMemoryRuntimeTest {
    private val now = Instant.parse("2026-09-12T12:00:00Z")

    @Test
    fun crashAfterEvidenceBeforeCursorReplaysWithoutDuplicateSourceRecord() = runTest {
        val backing = MemoryPhotonRepository()
        val repository = FailCheckpointOnceRepository(backing)
        val descriptor = LifeSourceDescriptor("calendar", "adapter-v1")
        val record = LifeSourceRecord(
            sourceId = "calendar",
            recordId = "event-42",
            observedAt = now.minusSeconds(60),
            payload = "Appointment",
            tags = setOf("event"),
        )
        val ingestor = DurableLifeSourceIngestor(repository)

        assertFailsWith<IllegalStateException> {
            ingestor.ingest(descriptor, listOf(record), "cursor-1", authorized = true, committedAt = now)
        }
        assertEquals(1, backing.loadAll().count { "life-source-evidence" in it.tags })
        assertNull(backing.loadAll().singleOrNull { "life-source-checkpoint" in it.tags })

        repository.failCheckpoint = false
        val recovered = ingestor.ingest(
            descriptor,
            listOf(record),
            "cursor-1",
            authorized = true,
            committedAt = now.plusSeconds(1),
        )

        assertEquals(1, backing.loadAll().count { "life-source-evidence" in it.tags })
        assertEquals("cursor-1", recovered.cursorAfter.position)
        assertNotNull(backing.loadAll().singleOrNull { "life-source-checkpoint" in it.tags })
    }

    @Test
    fun unauthorizedSourceCreatesGapWithoutPerceptionOrCursorAdvance() = runTest {
        val repository = MemoryPhotonRepository()
        val descriptor = LifeSourceDescriptor("contacts", "adapter-v2")
        val ingestor = DurableLifeSourceIngestor(repository)

        val result = ingestor.ingest(
            descriptor = descriptor,
            records = listOf(
                LifeSourceRecord("contacts", "person-1", now, "Alice", tags = setOf("person:Alice"))
            ),
            nextPosition = "should-not-advance",
            authorized = false,
            committedAt = now,
        )

        assertEquals(null, result.cursorAfter.position)
        assertTrue(result.evidencePhotons.isEmpty())
        assertNotNull(result.permissionGap)
        assertTrue(repository.loadAll().none { "life-source-evidence" in it.tags })
        assertTrue(repository.loadAll().any { "permission-state:unauthorized" in it.tags })
    }

    @Test
    fun sameSourceRecordDedupesAcrossLaterCursorAndRestart() = runTest {
        val repository = MemoryPhotonRepository()
        val descriptor = LifeSourceDescriptor("mail", "adapter-v1")
        val record = LifeSourceRecord("mail", "m-1", now, "Message", tags = setOf("document:message"))

        DurableLifeSourceIngestor(repository).ingest(
            descriptor,
            listOf(record),
            "p1",
            authorized = true,
            committedAt = now,
        )
        DurableLifeSourceIngestor(repository).ingest(
            descriptor,
            listOf(record),
            "p2",
            authorized = true,
            committedAt = now.plusSeconds(10),
        )

        assertEquals(1, repository.loadAll().count { "life-source-evidence" in it.tags })
        val checkpoint = PhotonBackedLifeSourceCheckpointStore(repository).load(descriptor)
        assertEquals("p2", checkpoint.position)
    }

    @Test
    fun accessLedgerIsAppendOnlyReplaySafeAndRebuildable() = runTest {
        val repository = MemoryPhotonRepository()
        val target = photon("target", now.minus(Duration.ofDays(500)), "old evidence")
        repository.save(target)
        val store = PhotonBackedMemoryAccessLedgerStore(repository)

        store.recordAccess(
            photonId = target.id,
            accessKey = "goal:g1:future-relevance",
            at = now,
            futureRelevance = 0.95,
        )
        store.recordAccess(
            photonId = target.id,
            accessKey = "goal:g1:future-relevance",
            at = now,
            futureRelevance = 0.95,
        )

        val rebuilt = PhotonBackedMemoryAccessLedgerStore(repository).snapshot()
        val profile = rebuilt.profiles.getValue(target.id)
        assertEquals(1L, profile.accessCount)
        assertEquals(0.95, profile.futureRelevance)
        assertEquals(now, profile.lastAccessAt)
    }

    @Test
    fun coldRestartKeepsGraphAndDurableProjectionFingerprintStable() = runTest {
        val repository = MemoryPhotonRepository()
        val source = photon(
            "history",
            now.minus(Duration.ofDays(400)),
            "Completed project event.",
            tags = setOf("completed", "event", "project:LifeOS"),
            semanticMass = 0.1,
            confidence = 0.4,
        )
        repository.save(source)
        val first = DurableLifeMemoryRuntime(repository).rebuild(now)
        val sourceBefore = requireNotNull(repository.load(source.id))
        val derivedCount = repository.loadAll().count { "memory-atom" in it.tags || "memory-crystal" in it.tags }

        val second = DurableLifeMemoryRuntime(repository).rebuild(now.plus(Duration.ofHours(1)))

        assertEquals(first.graph.fingerprint, second.graph.fingerprint)
        assertEquals(first.memory.fingerprint, second.memory.fingerprint)
        assertEquals(first.fingerprint, second.fingerprint)
        assertEquals(derivedCount, repository.loadAll().count { "memory-atom" in it.tags || "memory-crystal" in it.tags })
        assertEquals(sourceBefore, repository.load(source.id))
        assertTrue(repository.loadAll().filter { "memory-atom" in it.tags || "memory-crystal" in it.tags }.all {
            it.tags.any { tag -> tag.startsWith("source-state:") } &&
                "producer-version:${LongTermMemoryEngine.RUNTIME_VERSION}" in it.tags
        })
    }

    @Test
    fun futureRelevanceRehydratesPersistedOldMemoryDeterministically() = runTest {
        val repository = MemoryPhotonRepository()
        val source = photon(
            "future-old",
            now.minus(Duration.ofDays(500)),
            "Old completed project information.",
            tags = setOf("completed", "project:LifeOS"),
            semanticMass = 0.1,
            confidence = 0.4,
        )
        repository.save(source)
        val runtime = DurableLifeMemoryRuntime(repository)
        val cold = runtime.rebuild(now)
        assertEquals(MemoryStage.CRYSTALLIZED, cold.memory.stageOf(source.id))

        val rehydrated = runtime.recordAccess(
            photonId = source.id,
            accessKey = "future-plan:source",
            at = now.plusSeconds(1),
            futureRelevance = 0.95,
        )

        assertEquals(MemoryStage.WARM, rehydrated.memory.stageOf(source.id))
        assertEquals("rehydrated-by-future-or-goal-relevance", rehydrated.memory.decisions.single().reason)
    }

    @Test
    fun changedRecordBehindSameSourceIdentityFailsClosed() = runTest {
        val repository = MemoryPhotonRepository()
        val descriptor = LifeSourceDescriptor("calendar", "adapter-v1")
        val ingestor = DurableLifeSourceIngestor(repository)
        ingestor.ingest(
            descriptor,
            listOf(LifeSourceRecord("calendar", "r1", now, "Original")),
            "p1",
            authorized = true,
            committedAt = now,
        )

        assertFailsWith<IllegalStateException> {
            ingestor.ingest(
                descriptor,
                listOf(LifeSourceRecord("calendar", "r1", now, "Changed")),
                "p2",
                authorized = true,
                committedAt = now.plusSeconds(1),
            )
        }
    }

    private fun photon(
        id: String,
        createdAt: Instant,
        content: String,
        tags: Set<String> = emptySet(),
        semanticMass: Double = 0.2,
        confidence: Double = 0.5,
    ): Photon = Photon(
        id = PhotonId(id),
        content = content,
        semanticMass = semanticMass,
        confidence = confidence,
        provenance = Provenance("test", "user", createdAt),
        tags = tags,
    )

    private class MemoryPhotonRepository : PhotonRepository {
        private val data = linkedMapOf<PhotonId, Photon>()
        override suspend fun save(photon: Photon) { data[photon.id] = photon }
        override suspend fun load(id: PhotonId): Photon? = data[id]
        override suspend fun loadReport(): PhotonLoadReport = PhotonLoadReport(data.values.toList(), emptyList())
        override suspend fun loadAll(): List<Photon> = data.values.toList()
        override suspend fun delete(id: PhotonId) { data.remove(id) }
    }

    private class FailCheckpointOnceRepository(
        private val delegate: MemoryPhotonRepository,
    ) : PhotonRepository {
        var failCheckpoint: Boolean = true
        override suspend fun save(photon: Photon) {
            if (failCheckpoint && "life-source-checkpoint" in photon.tags) {
                throw IllegalStateException("simulated checkpoint crash")
            }
            delegate.save(photon)
        }
        override suspend fun load(id: PhotonId): Photon? = delegate.load(id)
        override suspend fun loadReport(): PhotonLoadReport = delegate.loadReport()
        override suspend fun loadAll(): List<Photon> = delegate.loadAll()
        override suspend fun delete(id: PhotonId) = delegate.delete(id)
    }
}
