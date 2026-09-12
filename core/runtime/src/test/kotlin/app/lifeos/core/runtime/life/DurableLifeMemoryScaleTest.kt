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
import kotlin.test.assertTrue

class DurableLifeMemoryScaleTest {
    private val now = Instant.parse("2026-09-12T12:00:00Z")

    @Test
    fun largeMixedMemorySetKeepsDeterministicFingerprintAcrossRecreation() = runTest {
        val repository = MemoryPhotonRepository()
        repeat(240) { index ->
            val age = when (index % 4) {
                0 -> Duration.ofHours(1)
                1 -> Duration.ofDays(10)
                2 -> Duration.ofDays(90)
                else -> Duration.ofDays(400)
            }
            val tags = buildSet {
                add("project:p${index % 12}")
                add("person:user${index % 17}")
                when (index % 7) {
                    0 -> add("fact:deadline")
                    1 -> add("goal")
                    2 -> add("completed")
                }
            }
            repository.save(
                Photon(
                    id = PhotonId("mixed-$index"),
                    content = "memory item $index",
                    semanticMass = (index % 10) / 10.0,
                    confidence = 0.5 + ((index % 5) * 0.1),
                    provenance = Provenance("test", "user", now.minus(age)),
                    tags = tags,
                )
            )
        }

        val first = DurableLifeMemoryRuntime(repository).rebuild(now)
        val second = DurableLifeMemoryRuntime(repository).rebuild(now)

        assertEquals(first.fingerprint, second.fingerprint)
        assertEquals(first.graph.fingerprint, second.graph.fingerprint)
        assertEquals(first.memory.fingerprint, second.memory.fingerprint)
        assertEquals(240, first.authoritativePhotonCount)
    }

    @Test
    fun unavailableSourceIsExplicitAndDoesNotAdvanceDurableCursor() = runTest {
        val repository = MemoryPhotonRepository()
        val descriptor = LifeSourceDescriptor("files", "adapter-v3")
        val ingestor = DurableLifeSourceIngestor(repository)
        ingestor.ingest(
            descriptor = descriptor,
            records = listOf(LifeSourceRecord("files", "f1", now, "available first")),
            nextPosition = "cursor-1",
            authorized = true,
            committedAt = now,
        )

        val gap = ingestor.recordUnavailable(descriptor, now.plusSeconds(1))
        val checkpoint = PhotonBackedLifeSourceCheckpointStore(repository).load(descriptor)

        assertTrue("permission-state:unavailable" in gap.tags)
        assertEquals("cursor-1", checkpoint.position)
        assertEquals("adapter-v3", checkpoint.cursor.adapterVersion)
        assertEquals(1, repository.loadAll().count { "life-source-evidence" in it.tags })
    }

    private class MemoryPhotonRepository : PhotonRepository {
        private val data = linkedMapOf<PhotonId, Photon>()
        override suspend fun save(photon: Photon) { data[photon.id] = photon }
        override suspend fun load(id: PhotonId): Photon? = data[id]
        override suspend fun loadReport(): PhotonLoadReport = PhotonLoadReport(data.values.toList(), emptyList())
        override suspend fun loadAll(): List<Photon> = data.values.toList()
        override suspend fun delete(id: PhotonId) { data.remove(id) }
    }
}
