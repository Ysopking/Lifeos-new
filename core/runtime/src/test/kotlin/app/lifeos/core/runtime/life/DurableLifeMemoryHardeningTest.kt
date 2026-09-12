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

class DurableLifeMemoryHardeningTest {
    private val now = Instant.parse("2026-09-12T12:00:00Z")

    @Test
    fun crashBeforeFirstEvidenceWriteLeavesCursorUnadvancedAndRetryRecovers() = runTest {
        val backing = MemoryPhotonRepository()
        val failing = FailSourceRecordOnceRepository(backing)
        val descriptor = LifeSourceDescriptor("calendar", "adapter-v3")
        val record = LifeSourceRecord("calendar", "r-1", now, "Meeting")

        assertFailsWith<IllegalStateException> {
            DurableLifeSourceIngestor(failing).ingest(
                descriptor,
                listOf(record),
                "cursor-1",
                authorized = true,
                committedAt = now,
            )
        }
        assertTrue(backing.loadAll().none { "life-source-evidence" in it.tags })
        assertNull(backing.loadAll().singleOrNull { "life-source-checkpoint" in it.tags })

        failing.failSourceRecord = false
        val recovered = DurableLifeSourceIngestor(failing).ingest(
            descriptor,
            listOf(record),
            "cursor-1",
            authorized = true,
            committedAt = now.plusSeconds(1),
        )

        assertEquals("cursor-1", recovered.cursorAfter.position)
        assertEquals("adapter-v3", recovered.cursorAfter.adapterVersion)
        assertEquals(1, backing.loadAll().count { "life-source-evidence" in it.tags })
    }

    @Test
    fun durableIdsAreCompatibleWithEncryptedPhotonVaultFilenameContract() = runTest {
        val repository = MemoryPhotonRepository()
        val descriptor = LifeSourceDescriptor("mail", "adapter-v1")
        val old = LifeSourceRecord(
            sourceId = "mail",
            recordId = "message-1",
            observedAt = now.minus(Duration.ofDays(400)),
            payload = "Completed historical event.",
            tags = setOf("completed", "event", "project:LifeOS"),
            confidence = 0.4,
        )
        val runtime = DurableLifeMemoryRuntime(repository)

        runtime.ingest(descriptor, listOf(old), "p1", authorized = true, committedAt = now)
        runtime.recordAccess(
            photonId = requireNotNull(repository.loadAll().singleOrNull { "life-source-evidence" in it.tags }).id,
            accessKey = "test-access",
            at = now,
        )

        val persisted = repository.loadAll().filter {
            "life-memory-management" in it.tags ||
                "life-source-evidence" in it.tags ||
                "memory-atom" in it.tags ||
                "memory-crystal" in it.tags
        }
        assertTrue(persisted.isNotEmpty())
        assertTrue(persisted.all { it.id.value.matches(Regex("[A-Za-z0-9_-]{1,128}")) })
    }

    @Test
    fun futureLineageRehydratesOldMemoryWithoutVolatileAccessState() = runTest {
        val repository = MemoryPhotonRepository()
        val source = photon(
            id = "old-source",
            createdAt = now.minus(Duration.ofDays(500)),
            content = "Old completed project context.",
            tags = setOf("completed", "project:LifeOS"),
            semanticMass = 0.1,
            confidence = 0.4,
        )
        val future = Photon(
            id = PhotonId("future-reference"),
            content = "Possible future project pressure.",
            confidence = 0.95,
            provenance = Provenance(
                source = "future-evidence",
                actor = "lifeos",
                createdAt = now,
                parentIds = setOf(source.id),
            ),
            tags = setOf("future-evidence", "future-observation"),
        )
        repository.save(source)
        repository.save(future)

        val rebuilt = DurableLifeMemoryRuntime(repository).rebuild(now)

        assertEquals(MemoryStage.WARM, rebuilt.memory.stageOf(source.id))
        assertEquals(0.95, rebuilt.accessLedger.profiles.getValue(source.id).futureRelevance)
        assertTrue(repository.loadAll().none { "memory-access-event" in it.tags })
    }

    @Test
    fun evidenceBackedAliasAndRelationshipAreDeterministicAndAmbiguityStaysSeparate() {
        val sourceA = photon(
            id = "alias-a",
            createdAt = now,
            content = "Alice is also known as Ally.",
            tags = setOf(
                "person:Ally",
                "person:Bob",
                "entity-alias:person:Ally=Alice",
                "entity-relationship:friend|person:Ally|person:Bob",
            ),
            confidence = 0.9,
        )
        val graph = LifeGraphProjector().project(listOf(sourceA))

        val alice = graph.entities.single { it.label == "Alice" }
        assertTrue("Ally" in alice.aliases)
        assertTrue(graph.relationships.any { it.type == "FRIEND" && sourceA.id in it.sourcePhotonIds })

        val conflict = photon(
            id = "alias-conflict",
            createdAt = now.plusSeconds(1),
            content = "Conflicting alias evidence.",
            tags = setOf("person:Ally", "entity-alias:person:Ally=Alicia"),
            confidence = 0.9,
        )
        val ambiguous = LifeGraphProjector().project(listOf(sourceA, conflict))
        assertTrue(ambiguous.entities.any { it.label == "Ally" })
    }

    @Test
    fun largeMixedMemorySetRebuildsWithStableFingerprint() = runTest {
        val repository = MemoryPhotonRepository()
        repeat(240) { index ->
            repository.save(
                photon(
                    id = "history-$index",
                    createdAt = now.minus(Duration.ofDays((220 + index).toLong())),
                    content = "Completed historical item $index.",
                    tags = setOf("completed", "event", "project:p${index % 7}"),
                    semanticMass = 0.1,
                    confidence = 0.5,
                )
            )
        }

        val first = DurableLifeMemoryRuntime(repository).rebuild(now)
        val second = DurableLifeMemoryRuntime(repository).rebuild(now)

        assertEquals(first.graph.fingerprint, second.graph.fingerprint)
        assertEquals(first.memory.fingerprint, second.memory.fingerprint)
        assertEquals(first.fingerprint, second.fingerprint)
        assertEquals(first.persistedMemoryIds(), second.persistedMemoryIds())
    }

    private fun DurableLifeMemorySnapshot.persistedMemoryIds(): List<PhotonId> =
        memory.derivedPhotons.map { it.id }.sortedBy { it.value }

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

    private class FailSourceRecordOnceRepository(
        private val delegate: MemoryPhotonRepository,
    ) : PhotonRepository {
        var failSourceRecord = true
        override suspend fun save(photon: Photon) {
            if (failSourceRecord && "life-source-evidence" in photon.tags) {
                throw IllegalStateException("simulated source evidence crash")
            }
            delegate.save(photon)
        }
        override suspend fun load(id: PhotonId): Photon? = delegate.load(id)
        override suspend fun loadReport(): PhotonLoadReport = delegate.loadReport()
        override suspend fun loadAll(): List<Photon> = delegate.loadAll()
        override suspend fun delete(id: PhotonId) = delegate.delete(id)
    }
}
