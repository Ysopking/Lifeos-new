package app.lifeos.core.runtime.life

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.Provenance
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActiveLifeSourceProjectionTest {
    private val now = Instant.parse("2026-09-15T08:00:00Z")

    @Test
    fun `completed v1 stays active while v2 migration is partial then switches atomically`() = runTest {
        val repository = MemoryPhotonRepository()
        val ingestor = DurableLifeSourceIngestor(repository)
        val runtime = DurableLifeMemoryRuntime(repository)
        val v1 = LifeSourceDescriptor("android-shared-files", "android-shared-files/v1")
        val v2 = LifeSourceDescriptor("android-shared-files", "android-shared-files/v2")

        ingestor.ingest(
            descriptor = v1,
            records = listOf(record(v1.sourceId, "legacy-file", "Legacy document", "LegacyPerson")),
            nextPosition = null,
            authorized = true,
            committedAt = now,
        )
        val beforeMigration = runtime.rebuild(now)
        assertTrue(beforeMigration.graph.entities.any { it.label == "LegacyPerson" })

        ingestor.ingest(
            descriptor = v2,
            records = listOf(record(v2.sourceId, "metadata-file", "Metadata document", "MetadataPerson")),
            nextPosition = "page-2",
            authorized = true,
            committedAt = now.plusSeconds(10),
        )
        val partialMigration = runtime.rebuild(now.plusSeconds(10))

        assertTrue(partialMigration.graph.entities.any { it.label == "LegacyPerson" })
        assertFalse(partialMigration.graph.entities.any { it.label == "MetadataPerson" })
        assertEquals("android-shared-files/v1", ActiveLifeSourceProjection.activeAdapters(repository.loadAll())[v1.sourceId])
        assertEquals(2, repository.loadAll().count { "life-source-evidence" in it.tags })

        ingestor.ingest(
            descriptor = v2,
            records = emptyList(),
            nextPosition = null,
            authorized = true,
            committedAt = now.plusSeconds(20),
        )
        val completedMigration = runtime.rebuild(now.plusSeconds(20))

        assertFalse(completedMigration.graph.entities.any { it.label == "LegacyPerson" })
        assertTrue(completedMigration.graph.entities.any { it.label == "MetadataPerson" })
        assertEquals("android-shared-files/v2", ActiveLifeSourceProjection.activeAdapters(repository.loadAll())[v1.sourceId])
        assertEquals(2, repository.loadAll().count { "life-source-evidence" in it.tags })
    }

    @Test
    fun `retired legacy source evidence stays durable but leaves graph and memory`() = runTest {
        val repository = MemoryPhotonRepository()
        val descriptor =
            LifeSourceDescriptor(
                "android-shared-files",
                "android-shared-files/v2",
            )
        val ingestor = DurableLifeSourceIngestor(repository)
        val runtime = DurableLifeMemoryRuntime(repository)

        ingestor.ingest(
            descriptor = descriptor,
            records = listOf(
                record(
                    descriptor.sourceId,
                    "metadata-file",
                    "Legacy metadata",
                    "LegacyMetadataPerson",
                )
            ),
            nextPosition = null,
            authorized = true,
            committedAt = now,
        )

        val before = runtime.rebuild(now)
        assertTrue(
            before.graph.entities.any {
                it.label == "LegacyMetadataPerson"
            }
        )

        val retired = runtime.retireSourceEvidence(
            sourceId = descriptor.sourceId,
            replacementAuthority =
                "live-data:android-files",
            at = now.plusSeconds(1),
        )

        assertFalse(
            retired.graph.entities.any {
                it.label == "LegacyMetadataPerson"
            }
        )
        assertTrue(
            repository.loadAll().any {
                "life-source-evidence" in it.tags &&
                    "source:android-shared-files" in it.tags
            }
        )
        assertEquals(
            setOf("android-shared-files"),
            DurableLifeSourceRetirement.retiredSources(
                repository.loadAll()
            ),
        )

        val markerCount = repository.loadAll().count {
            "life-source-retirement" in it.tags
        }
        val replay = runtime.retireSourceEvidence(
            sourceId = descriptor.sourceId,
            replacementAuthority =
                "live-data:android-files",
            at = now.plusSeconds(2),
        )

        assertEquals(1, markerCount)
        assertEquals(
            markerCount,
            repository.loadAll().count {
                "life-source-retirement" in it.tags
            },
        )
        assertEquals(retired.fingerprint, replay.fingerprint)
    }

    @Test
    fun `malformed retirement marker cannot hide source evidence`() = runTest {
        val repository = MemoryPhotonRepository()
        val evidence = Photon(
            id = PhotonId("legacy-evidence-with-bad-retirement"),
            content = "Still authoritative",
            provenance =
                Provenance("test", "legacy", now),
            tags = setOf(
                "life-source-evidence",
                "source:android-shared-files",
                "source-adapter:android-shared-files/v2",
                "person:StillAuthoritative",
            ),
        )
        repository.save(evidence)
        repository.save(
            Photon(
                id = PhotonId("malformed-retirement"),
                content = "schema=999\nsource=broken",
                provenance =
                    Provenance(
                        "life-source-retirement",
                        "broken",
                        now.plusSeconds(1),
                    ),
                tags = setOf(
                    "life-memory-management",
                    "life-source-retirement",
                    "source:android-shared-files",
                ),
            )
        )

        val filtered = ActiveLifeSourceProjection.filter(
            authoritative = listOf(evidence),
            allPhotons = repository.loadAll(),
        )

        assertEquals(listOf(evidence), filtered)
        assertTrue(
            DurableLifeMemoryRuntime(repository)
                .rebuild(now.plusSeconds(1))
                .graph.entities
                .any { it.label == "StillAuthoritative" }
        )
    }

    @Test
    fun `source evidence without a valid checkpoint stays visible`() = runTest {
        val repository = MemoryPhotonRepository()
        repository.save(
            Photon(
                id = PhotonId("legacy-source-without-checkpoint"),
                content = "Imported legacy source",
                provenance = Provenance("test", "legacy-adapter", now),
                tags = setOf(
                    "life-source-evidence",
                    "source:legacy-source",
                    "source-adapter:legacy/v0",
                    "person:StillVisible",
                ),
            )
        )

        val snapshot = DurableLifeMemoryRuntime(repository).rebuild(now)

        assertTrue(snapshot.graph.entities.any { it.label == "StillVisible" })
        assertEquals(1, snapshot.authoritativePhotonCount)
    }

    @Test
    fun `malformed checkpoint cannot hide otherwise authoritative evidence`() = runTest {
        val repository = MemoryPhotonRepository()
        val evidence = Photon(
            id = PhotonId("source-evidence"),
            content = "Visible evidence",
            provenance = Provenance("test", "adapter-v1", now),
            tags = setOf(
                "life-source-evidence",
                "source:files",
                "source-adapter:adapter-v1",
                "person:VisiblePerson",
            ),
        )
        repository.save(evidence)
        repository.save(
            Photon(
                id = PhotonId("bad-checkpoint"),
                content = "schema=999\nsource=broken",
                provenance = Provenance("life-source-checkpoint", "files", now.plusSeconds(1)),
                tags = setOf(
                    "life-memory-management",
                    "life-source-checkpoint",
                    "source:files",
                    "source-adapter:adapter-v2",
                ),
            )
        )

        val active = ActiveLifeSourceProjection.filter(listOf(evidence), repository.loadAll())
        val snapshot = DurableLifeMemoryRuntime(repository).rebuild(now.plusSeconds(1))

        assertEquals(listOf(evidence), active)
        assertTrue(snapshot.graph.entities.any { it.label == "VisiblePerson" })
    }

    private fun record(
        sourceId: String,
        recordId: String,
        payload: String,
        person: String,
    ) = LifeSourceRecord(
        sourceId = sourceId,
        recordId = recordId,
        observedAt = now,
        payload = payload,
        tags = setOf("person:$person"),
    )

    private class MemoryPhotonRepository : PhotonRepository {
        private val data = linkedMapOf<PhotonId, Photon>()

        override suspend fun save(photon: Photon) {
            data[photon.id] = photon
        }

        override suspend fun load(id: PhotonId): Photon? = data[id]

        override suspend fun loadReport(): PhotonLoadReport = PhotonLoadReport(data.values.toList(), emptyList())

        override suspend fun loadAll(): List<Photon> = data.values.toList()

        override suspend fun delete(id: PhotonId) {
            data.remove(id)
        }
    }
}
