package app.lifeos.core.runtime.extension

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.PhotonIndexReport
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.PhotonRevisionWriteResult
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RevisionedPhotonRepository
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ExtensionPhotonReaderTest {
    @Test
    fun loadsOnlyExactIndexedRevisionsWithinHardBound() = runTest {
        val first = photon("p1", revision = 2)
        val second = photon("p2", revision = 7)
        val repository = RecordingRepository(listOf(first, second))

        val result = ExtensionPhotonReader(repository).query(
            ExtensionPhotonQuery(
                allTags = setOf("extension-visible"),
                limit = 2,
            )
        )

        assertEquals(
            listOf(
                PhotonRevisionRef(first.id, first.revision),
                PhotonRevisionRef(second.id, second.revision),
            ),
            result.refs,
        )
        assertEquals(listOf(first, second), result.photons)
        assertEquals(result.refs, repository.loadedRefs)
        assertFalse(repository.loadAllCalled)
        assertEquals(2, repository.lastQuery?.limit)
        assertFalse(repository.lastQuery?.includeTombstoned ?: true)
    }

    @Test
    fun rejectsUnboundedExtensionQueries() {
        assertFailsWith<IllegalArgumentException> {
            ExtensionPhotonQuery(limit = ExtensionPhotonQuery.MAX_LIMIT + 1)
        }
    }

    @Test
    fun failsClosedWhenIndexRefCannotBeLoadedExactly() = runTest {
        val ref = PhotonRevisionRef(PhotonId("missing"), 3)
        val repository = RecordingRepository(emptyList(), forcedRefs = listOf(ref))

        assertFailsWith<IllegalArgumentException> {
            ExtensionPhotonReader(repository).query(ExtensionPhotonQuery(limit = 1))
        }
    }

    private fun photon(id: String, revision: Long): Photon = Photon(
        id = PhotonId(id),
        revision = revision,
        content = "content-$id-$revision",
        provenance = Provenance(
            source = "test",
            actor = "test",
            createdAt = Instant.EPOCH,
        ),
        tags = setOf("extension-visible"),
    )

    private class RecordingRepository(
        initial: List<Photon>,
        private val forcedRefs: List<PhotonRevisionRef>? = null,
    ) : RevisionedPhotonRepository {
        private val byRef = initial.associateBy { PhotonRevisionRef(it.id, it.revision) }
        val loadedRefs = mutableListOf<PhotonRevisionRef>()
        var lastQuery: PhotonIndexQuery? = null
            private set
        var loadAllCalled = false
            private set

        override suspend fun query(query: PhotonIndexQuery): List<PhotonRevisionRef> {
            lastQuery = query
            return forcedRefs ?: byRef.keys.take(query.limit)
        }

        override suspend fun load(ref: PhotonRevisionRef): Photon? {
            loadedRefs += ref
            return byRef[ref]
        }

        override suspend fun load(id: PhotonId): Photon? =
            byRef.values.firstOrNull { it.id == id }

        override suspend fun latestRef(id: PhotonId): PhotonRevisionRef? =
            byRef.keys.firstOrNull { it.photonId == id }

        override suspend fun loadReport(): PhotonLoadReport =
            PhotonLoadReport(byRef.values.toList(), emptyList())

        override suspend fun loadAll(): List<Photon> {
            loadAllCalled = true
            return byRef.values.toList()
        }

        override suspend fun save(photon: Photon) {
            error("not used")
        }

        override suspend fun saveRevision(
            photon: Photon,
            expectedPreviousRevision: Long?,
        ): PhotonRevisionWriteResult = error("not used")

        override suspend fun delete(id: PhotonId) {
            error("not used")
        }

        override suspend fun indexReport(): PhotonIndexReport = error("not used")
    }
}
