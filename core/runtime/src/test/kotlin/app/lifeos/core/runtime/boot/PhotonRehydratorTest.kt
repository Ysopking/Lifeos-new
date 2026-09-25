package app.lifeos.core.runtime.boot

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonIndexOrder
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.PhotonIndexReport
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.PhotonRevisionWriteResult
import app.lifeos.core.model.RevisionedPhotonRepository
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhotonRehydratorTest {
    @Test
    fun tiersPhotonsAndPreservesUnreadableFiles() = runTest {
        val active = photon(content = "active", phase = PhotonPhase.ACTIVE)
        val archived = photon(content = "archived", phase = PhotonPhase.ARCHIVED)
        val orphan = photon(
            content = "orphan",
            phase = PhotonPhase.CREATED,
            relations = setOf(
                PhotonRelation(
                    target = PhotonId("missing"),
                    type = RelationType.REFERENCES,
                )
            ),
        )
        val repository = FakePhotonRepository(
            photons = mutableListOf(active, archived, orphan),
            unreadable = listOf("broken.photon"),
        )

        val result = PhotonRehydrator(repository).rehydrate()

        assertEquals(listOf(active), result.hot)
        assertEquals(listOf(orphan), result.warm)
        assertEquals(listOf(archived.id), result.cold)
        assertEquals(listOf("broken.photon"), result.unreadableFiles)
        assertEquals(PhotonIntegrityState.ORPHANED, result.assessments.single { it.photonId == orphan.id }.state)
    }

    @Test
    fun criticalHydrationLoadsOnlyBoundedHotHeadsAndDefersTheRest() = runTest {
        val hot = photon(content = "hot", phase = PhotonPhase.ACTIVE)
        val warm = photon(
            content = "warm",
            relations = setOf(
                PhotonRelation(target = hot.id, type = RelationType.REFERENCES)
            ),
        )
        val archived = photon(content = "archived", phase = PhotonPhase.ARCHIVED)
        val repository = FakeRevisionedPhotonRepository(
            mutableListOf(hot, warm, archived)
        )
        val rehydrator = PhotonRehydrator(
            repository = repository,
            criticalHydration = true,
            criticalHotLimit = 1,
        )

        val critical = rehydrator.rehydrate()

        assertEquals(listOf(hot), critical.hot)
        assertTrue(critical.warm.isEmpty())
        assertEquals(listOf(hot), critical.allPhotons)
        assertEquals(setOf(warm.id, archived.id), critical.deferredRefs.map { it.photonId }.toSet())
        assertEquals(0, repository.fullLoadCount)

        val full = rehydrator.rehydrateAll()
        assertEquals(listOf(warm), full.warm)
        assertEquals(listOf(archived.id), full.cold)
        assertEquals(1, repository.fullLoadCount)
    }

    @Test
    fun duplicatePhotonIdsAreQuarantinedInsteadOfDeleted() = runTest {
        val id = PhotonId("duplicate")
        val first = photon(id = id, content = "one")
        val second = photon(id = id, content = "two")
        val repository = FakePhotonRepository(mutableListOf(first, second))

        val result = PhotonRehydrator(repository).rehydrate()

        assertTrue(id in result.quarantined)
        assertEquals(0, result.restoredCount)
        assertEquals(2, repository.photons.size)
    }

    private fun photon(
        id: PhotonId = PhotonId.new(),
        content: String,
        phase: PhotonPhase = PhotonPhase.CREATED,
        relations: Set<PhotonRelation> = emptySet(),
    ) = Photon(
        id = id,
        content = content,
        phase = phase,
        provenance = Provenance(source = "test", actor = "test"),
        relations = relations,
    )

    private class FakeRevisionedPhotonRepository(
        val photons: MutableList<Photon>,
    ) : RevisionedPhotonRepository {
        var fullLoadCount: Int = 0
            private set

        override suspend fun save(photon: Photon) {
            photons.removeAll { it.id == photon.id && it.revision == photon.revision }
            photons += photon
        }

        override suspend fun loadAll(): List<Photon> = photons.toList()

        override suspend fun delete(id: PhotonId) {
            photons.removeAll { it.id == id }
        }

        override suspend fun load(id: PhotonId): Photon? =
            photons.filter { it.id == id }.maxByOrNull { it.revision }

        override suspend fun load(ref: PhotonRevisionRef): Photon? =
            photons.singleOrNull { it.id == ref.photonId && it.revision == ref.revision }

        override suspend fun latestRef(id: PhotonId): PhotonRevisionRef? =
            load(id)?.let { PhotonRevisionRef(it.id, it.revision) }

        override suspend fun saveRevision(
            photon: Photon,
            expectedPreviousRevision: Long?,
        ): PhotonRevisionWriteResult = error("not used")

        override suspend fun loadReport(): PhotonLoadReport {
            fullLoadCount += 1
            return PhotonLoadReport(
                photons = latest(),
                unreadableFiles = emptyList(),
            )
        }

        override suspend fun indexReport(): PhotonIndexReport {
            val latest = latest()
            return PhotonIndexReport(
                formatVersion = 1,
                entryCount = latest.size,
                livePhotonCount = latest.size,
                tombstonedPhotonCount = 0,
                latestRefs = latest.associate { it.id to PhotonRevisionRef(it.id, it.revision) },
            )
        }

        override suspend fun query(query: PhotonIndexQuery): List<PhotonRevisionRef> {
            val values = latest()
                .asSequence()
                .filter { query.phases.isEmpty() || it.phase in query.phases }
                .filter { it.tags.containsAll(query.allTags) }
                .filter { query.anyTags.isEmpty() || it.tags.any(query.anyTags::contains) }
                .let { sequence ->
                    when (query.order) {
                        PhotonIndexOrder.HIGHEST_SEMANTIC_MASS ->
                            sequence.sortedByDescending { it.semanticMass }
                        PhotonIndexOrder.NEWEST_FIRST ->
                            sequence.sortedByDescending { it.provenance.createdAt }
                        PhotonIndexOrder.OLDEST_FIRST ->
                            sequence.sortedBy { it.provenance.createdAt }
                        PhotonIndexOrder.HIGHEST_CONFIDENCE ->
                            sequence.sortedByDescending { it.confidence }
                        PhotonIndexOrder.IDENTITY ->
                            sequence.sortedBy { it.id.value }
                    }
                }
                .map { PhotonRevisionRef(it.id, it.revision) }
                .toList()
            val afterIndex = query.after?.let { cursor ->
                values.indexOf(cursor.lastRef).takeIf { it >= 0 }?.plus(1)
            } ?: 0
            return values.drop(afterIndex).take(query.limit)
        }

        private fun latest(): List<Photon> =
            photons.groupBy { it.id }
                .values
                .map { revisions -> revisions.maxBy { it.revision } }
                .sortedBy { it.id.value }
    }

    private class FakePhotonRepository(
        val photons: MutableList<Photon>,
        private val unreadable: List<String> = emptyList(),
    ) : PhotonRepository {
        override suspend fun save(photon: Photon) {
            photons.removeAll { it.id == photon.id }
            photons += photon
        }

        override suspend fun loadAll(): List<Photon> = photons.toList()

        override suspend fun delete(id: PhotonId) {
            photons.removeAll { it.id == id }
        }

        override suspend fun load(id: PhotonId): Photon? = photons.lastOrNull { it.id == id }

        override suspend fun loadReport(): PhotonLoadReport = PhotonLoadReport(photons.toList(), unreadable)
    }
}
