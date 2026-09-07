package app.lifeos.core.runtime.boot

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.PhotonRepository
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
