package app.lifeos.core.runtime.field

import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.FieldSnapshot
import app.lifeos.core.field.FieldSnapshotId
import app.lifeos.core.field.FieldSnapshotLoadReport
import app.lifeos.core.field.FieldSnapshotRepository
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest

class UniversalFieldRuntimeAdapterTest {
    private val fixedTime = Instant.parse("2026-09-08T12:00:00Z")

    @Test
    fun `same immutable photon yields same run and snapshot ids`() = runTest {
        val repository = MemorySnapshotRepository()
        val adapter = UniversalFieldRuntimeAdapter(snapshotRepository = repository)
        val photon = photon(content = "same input")

        val first = adapter.process(photon)
        val second = adapter.process(photon)

        assertEquals(FieldShadowState.COMPLETED, first.state)
        assertEquals(first.runId, second.runId)
        assertEquals(first.snapshotId, second.snapshotId)
        assertEquals(1, repository.snapshots.size)
        assertNotNull(first.convergenceStatus)
    }

    @Test
    fun `content change changes universal field run identity`() = runTest {
        val repository = MemorySnapshotRepository()
        val adapter = UniversalFieldRuntimeAdapter(snapshotRepository = repository)

        val first = adapter.process(photon(content = "alpha"))
        val second = adapter.process(photon(content = "beta"))

        assertEquals(FieldShadowState.COMPLETED, first.state)
        assertEquals(FieldShadowState.COMPLETED, second.state)
        assertNotEquals(first.runId, second.runId)
        assertNotEquals(first.snapshotId, second.snapshotId)
    }

    @Test
    fun `snapshot persistence failure is observational and does not throw`() = runTest {
        val adapter = UniversalFieldRuntimeAdapter(
            snapshotRepository = object : FieldSnapshotRepository {
                override suspend fun save(snapshot: FieldSnapshot) {
                    error("vault unavailable")
                }

                override suspend fun load(id: FieldSnapshotId): FieldSnapshot? = null
                override suspend fun loadLatest(domainId: FieldDomainId): FieldSnapshot? = null
                override suspend fun loadReport(domainId: FieldDomainId?) =
                    FieldSnapshotLoadReport(emptyList(), emptyList())
                override suspend fun delete(id: FieldSnapshotId) = Unit
            },
        )

        val result = adapter.process(photon(content = "persist me"))

        assertEquals(FieldShadowState.FAILED, result.state)
        assertEquals("vault unavailable", result.message)
        assertEquals(null, result.runId)
        assertEquals(null, result.snapshotId)
    }

    @Test
    fun `request projection preserves source photon revision without mutation`() {
        val source = photon(content = "source", revision = 7)
        val before = source.copy()

        val request = DefaultPhotonFieldRequestFactory().create(source)

        assertEquals(source.id, request.evidence.single().sourcePhotonId)
        assertEquals(7, request.evidence.single().sourceRevision)
        assertEquals(before, source)
    }

    private fun photon(content: String, revision: Long = 1): Photon = Photon(
        id = PhotonId("shadow-test-photon"),
        revision = revision,
        content = content,
        semanticMass = 1.25,
        energy = 0.8,
        confidence = 0.9,
        provenance = Provenance(
            source = "test",
            actor = "test",
            createdAt = fixedTime,
        ),
    )

    private class MemorySnapshotRepository : FieldSnapshotRepository {
        val snapshots = linkedMapOf<FieldSnapshotId, FieldSnapshot>()

        override suspend fun save(snapshot: FieldSnapshot) {
            snapshots[snapshot.id] = snapshot
        }

        override suspend fun load(id: FieldSnapshotId): FieldSnapshot? = snapshots[id]

        override suspend fun loadLatest(domainId: FieldDomainId): FieldSnapshot? = snapshots.values
            .filter { it.domainId == domainId }
            .maxWithOrNull(
                compareBy<FieldSnapshot> { it.state.iteration.index }
                    .thenBy { it.runId.value }
                    .thenBy { it.id.value },
            )

        override suspend fun loadReport(domainId: FieldDomainId?): FieldSnapshotLoadReport =
            FieldSnapshotLoadReport(
                snapshots = snapshots.values.filter { domainId == null || it.domainId == domainId },
                unreadableEntries = emptyList(),
            )

        override suspend fun delete(id: FieldSnapshotId) {
            snapshots.remove(id)
        }
    }
}
