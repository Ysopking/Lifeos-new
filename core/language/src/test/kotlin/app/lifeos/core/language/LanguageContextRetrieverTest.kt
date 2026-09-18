package app.lifeos.core.language

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonIndexOrder
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.PhotonIndexReport
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.PhotonRevisionWriteResult
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RevisionedPhotonRepository
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class LanguageContextRetrieverTest {
    private val now = Instant.parse("2026-09-18T12:00:00Z")

    @Test
    fun `retrieval is bounded and never invokes full vault scan`() = runTest {
        val photons = (0 until 320).map { index ->
            Photon(
                id = PhotonId("p-$index"),
                revision = 1,
                content = if (index == 319) "Jobcenter Bescheid Hund relevant" else "unrelated text $index",
                phase = if (index % 9 == 0) PhotonPhase.ACTIVE else PhotonPhase.CONVERGED,
                semanticMass = 1.0 + (index % 12),
                confidence = 0.70 + (index % 20) / 100.0,
                provenance = Provenance(
                    source = "test",
                    actor = "test",
                    createdAt = now.minusSeconds((320 - index).toLong()),
                ),
                tags = buildSet {
                    if (index % 40 == 0) add("goal")
                    if (index % 55 == 0) add("life-matter")
                    if (index % 37 == 0) add("result")
                },
            )
        }
        val repository = FakeRevisionedRepository(photons)
        val retriever = LanguageContextRetriever(repository)

        val result = retriever.retrieve(
            utterance = "Was steht im Jobcenter Bescheid?",
            now = now,
        )

        assertEquals(0, repository.loadAllCalls)
        assertTrue(repository.queryCalls >= 6)
        assertTrue(result.trace.candidateRefs <= 160)
        assertTrue(result.trace.loadedPhotons <= 160)
        assertTrue(result.trace.selectedPhotons <= 96)
        assertTrue(result.context.items.size <= 96)
        assertTrue(result.context.items.any { "jobcenter" in it.normalizedTerms })
    }

    private class FakeRevisionedRepository(
        initial: List<Photon>,
    ) : RevisionedPhotonRepository {
        private val byRef = initial.associateBy { PhotonRevisionRef(it.id, it.revision) }.toMutableMap()
        var loadAllCalls: Int = 0
            private set
        var queryCalls: Int = 0
            private set

        override suspend fun save(photon: Photon) {
            byRef[PhotonRevisionRef(photon.id, photon.revision)] = photon
        }

        override suspend fun load(id: PhotonId): Photon? =
            byRef.values.filter { it.id == id }.maxByOrNull { it.revision }

        override suspend fun load(ref: PhotonRevisionRef): Photon? = byRef[ref]

        override suspend fun loadAll(): List<Photon> {
            loadAllCalls += 1
            error("LanguageContextRetriever must not call loadAll")
        }

        override suspend fun delete(id: PhotonId) {
            byRef.keys.filter { it.photonId == id }.forEach(byRef::remove)
        }

        override suspend fun loadReport(): PhotonLoadReport =
            PhotonLoadReport(byRef.values.toList(), emptyList())

        override suspend fun latestRef(id: PhotonId): PhotonRevisionRef? =
            byRef.keys.filter { it.photonId == id }.maxByOrNull { it.revision }

        override suspend fun saveRevision(
            photon: Photon,
            expectedPreviousRevision: Long?,
        ): PhotonRevisionWriteResult {
            val previous = load(photon.id)
            byRef[PhotonRevisionRef(photon.id, photon.revision)] = photon
            return if (previous == null) {
                PhotonRevisionWriteResult.Created(photon)
            } else {
                PhotonRevisionWriteResult.Advanced(photon, previous)
            }
        }

        override suspend fun query(query: PhotonIndexQuery): List<PhotonRevisionRef> {
            queryCalls += 1
            val latest = byRef.values
                .groupBy { it.id }
                .mapValues { (_, revisions) -> revisions.maxBy { it.revision } }
                .values
                .asSequence()
                .filter { query.ids.isEmpty() || it.id in query.ids }
                .filter { query.phases.isEmpty() || it.phase in query.phases }
                .filter { query.mimeTypes.isEmpty() || it.mimeType in query.mimeTypes }
                .filter { it.tags.containsAll(query.allTags) }
            val ordered = when (query.order) {
                PhotonIndexOrder.IDENTITY ->
                    latest.sortedWith(compareBy<Photon> { it.id.value }.thenBy { it.revision })
                PhotonIndexOrder.NEWEST_FIRST ->
                    latest.sortedByDescending { it.provenance.createdAt }
                PhotonIndexOrder.OLDEST_FIRST ->
                    latest.sortedBy { it.provenance.createdAt }
                PhotonIndexOrder.HIGHEST_SEMANTIC_MASS ->
                    latest.sortedByDescending { it.semanticMass }
                PhotonIndexOrder.HIGHEST_CONFIDENCE ->
                    latest.sortedByDescending { it.confidence }
            }
            return ordered.take(query.limit).map { PhotonRevisionRef(it.id, it.revision) }.toList()
        }

        override suspend fun indexReport(): PhotonIndexReport {
            val latest = byRef.keys
                .groupBy { it.photonId }
                .mapValues { (_, refs) -> refs.maxBy { it.revision } }
            return PhotonIndexReport(
                formatVersion = 1,
                entryCount = byRef.size,
                livePhotonCount = latest.size,
                tombstonedPhotonCount = 0,
                latestRefs = latest,
            )
        }
    }
}
