package app.lifeos.next.kernel

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonIndexOrder
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.PhotonIndexReport
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.PhotonRevisionWriteResult
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RevisionedPhotonRepository
import app.lifeos.core.runtime.PhotonIngressMode
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CanonicalLifePhotonRepositoryPaginationTest {
    @Test
    fun reconciliationTraversesMultipleBoundedTagPages() = runBlocking {
        val delegate = PagedRevisionedPhotonRepository()
        repeat(300) { index ->
            delegate.save(
                Photon(
                    id = PhotonId("life-evidence-$index"),
                    content = "evidence-$index",
                    provenance = Provenance(
                        source = "test",
                        actor = "lifeos",
                        createdAt = Instant.EPOCH.plusSeconds(index.toLong()),
                    ),
                    tags = setOf("life-source-evidence"),
                )
            )
        }

        val ingress = mutableListOf<Pair<PhotonId, PhotonIngressMode>>()
        val repository = CanonicalLifePhotonRepository(delegate) { photon, mode ->
            ingress += photon.id to mode
        }

        assertEquals(300, repository.reconcilePersisted())
        assertEquals(300, ingress.size)
        assertTrue(ingress.all { it.second == PhotonIngressMode.ORIGIN })
        assertTrue(delegate.queryCount >= 2)
        assertTrue(delegate.maxRequestedLimit <= PhotonIndexQuery.HARD_PAGE_LIMIT)
    }

    private class PagedRevisionedPhotonRepository : RevisionedPhotonRepository {
        private val data = linkedMapOf<PhotonRevisionRef, Photon>()
        var queryCount: Int = 0
            private set
        var maxRequestedLimit: Int = 0
            private set

        override suspend fun save(photon: Photon) {
            data[PhotonRevisionRef(photon.id, photon.revision)] = photon
        }

        override suspend fun load(id: PhotonId): Photon? =
            data.entries
                .asSequence()
                .filter { it.key.photonId == id }
                .maxByOrNull { it.key.revision }
                ?.value

        override suspend fun load(ref: PhotonRevisionRef): Photon? = data[ref]

        override suspend fun latestRef(id: PhotonId): PhotonRevisionRef? =
            data.keys
                .asSequence()
                .filter { it.photonId == id }
                .maxByOrNull { it.revision }

        override suspend fun saveRevision(
            photon: Photon,
            expectedPreviousRevision: Long?,
        ): PhotonRevisionWriteResult {
            val previous = expectedPreviousRevision?.let { data[PhotonRevisionRef(photon.id, it)] }
            data[PhotonRevisionRef(photon.id, photon.revision)] = photon
            return if (previous == null) {
                PhotonRevisionWriteResult.Created(photon)
            } else {
                PhotonRevisionWriteResult.Advanced(photon, previous)
            }
        }

        override suspend fun query(query: PhotonIndexQuery): List<PhotonRevisionRef> {
            queryCount += 1
            maxRequestedLimit = maxOf(maxRequestedLimit, query.limit)

            val photons = data.values
                .asSequence()
                .filter { query.ids.isEmpty() || it.id in query.ids }
                .filter { query.phases.isEmpty() || it.phase in query.phases }
                .filter { query.mimeTypes.isEmpty() || it.mimeType in query.mimeTypes }
                .filter { it.tags.containsAll(query.allTags) }
                .toList()

            val ordered = when (query.order) {
                PhotonIndexOrder.IDENTITY ->
                    photons.sortedWith(compareBy<Photon> { it.id.value }.thenBy { it.revision })
                PhotonIndexOrder.OLDEST_FIRST ->
                    photons.sortedWith(
                        compareBy<Photon> { it.provenance.createdAt }
                            .thenBy { it.id.value }
                            .thenBy { it.revision }
                    )
                else -> error("Unexpected order in reconciliation test: ${query.order}")
            }
            val refs = ordered.map { PhotonRevisionRef(it.id, it.revision) }
            val start = query.after?.let { cursor ->
                val position = refs.indexOf(cursor.lastRef)
                require(position >= 0)
                position + 1
            } ?: 0
            return refs.drop(start).take(query.limit)
        }

        override suspend fun indexReport(): PhotonIndexReport {
            val latest = data.keys
                .groupBy { it.photonId }
                .mapValues { (_, refs) -> refs.maxBy { it.revision } }
            return PhotonIndexReport(
                formatVersion = 1,
                entryCount = data.size,
                livePhotonCount = latest.size,
                tombstonedPhotonCount = 0,
                latestRefs = latest,
            )
        }

        override suspend fun loadReport(): PhotonLoadReport =
            PhotonLoadReport(data.values.toList(), emptyList())

        override suspend fun loadAll(): List<Photon> = data.values.toList()

        override suspend fun delete(id: PhotonId) {
            data.keys.filter { it.photonId == id }.forEach(data::remove)
        }
    }
}
