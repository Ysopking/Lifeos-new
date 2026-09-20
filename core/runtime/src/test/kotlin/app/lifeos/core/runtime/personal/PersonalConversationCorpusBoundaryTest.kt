package app.lifeos.core.runtime.personal

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonIndexOrder
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.PhotonIndexReport
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.PhotonRevisionWriteResult
import app.lifeos.core.model.RevisionedPhotonRepository
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PersonalConversationCorpusBoundaryTest {
    @Test
    fun `owner language retrieval excludes assistant and other turns`() = runTest {
        val repository = InMemoryRevisionedPhotonRepository()
        val importer = PersonalConversationCorpusImporter(repository)
        val observedAt = Instant.parse("2026-09-20T08:00:00Z")
        val turns = listOf(
            PersonalConversationTurn(
                source = PersonalConversationSource.GEMINI,
                conversationId = "gemini-1",
                speaker = PersonalConversationSpeaker.OWNER,
                text = "zieh das bitte komplett durch",
                observedAt = observedAt,
                externalMessageId = "u1",
            ),
            PersonalConversationTurn(
                source = PersonalConversationSource.GEMINI,
                conversationId = "gemini-1",
                speaker = PersonalConversationSpeaker.ASSISTANT,
                text = "ich ziehe das komplett durch",
                observedAt = observedAt.plusSeconds(1),
                externalMessageId = "a1",
            ),
            PersonalConversationTurn(
                source = PersonalConversationSource.WHATSAPP,
                conversationId = "wa-1",
                speaker = PersonalConversationSpeaker.OTHER,
                text = "zieh das bitte komplett durch",
                observedAt = observedAt.plusSeconds(2),
                externalMessageId = "w1",
            ),
        )

        val first = importer.import(turns)
        val second = importer.import(turns)

        assertEquals(3, first.created)
        assertEquals(0, first.replayed)
        assertEquals(0, second.created)
        assertEquals(3, second.replayed)

        val matches = PersonalCorpusRetriever(repository).retrieveOwnerLanguageExamples(
            query = "zieh komplett durch",
            now = observedAt.plusSeconds(10),
        )

        assertTrue(matches.isNotEmpty())
        assertTrue(matches.all { "speaker:owner" in it.photon.tags })
        assertTrue(matches.none { "speaker:assistant" in it.photon.tags })
        assertTrue(matches.none { "speaker:other" in it.photon.tags })
    }

    private class InMemoryRevisionedPhotonRepository : RevisionedPhotonRepository {
        private val photons = linkedMapOf<PhotonId, Photon>()

        override suspend fun save(photon: Photon) {
            photons[photon.id] = photon
        }

        override suspend fun loadAll(): List<Photon> = photons.values.toList()

        override suspend fun delete(id: PhotonId) {
            photons.remove(id)
        }

        override suspend fun load(id: PhotonId): Photon? = photons[id]

        override suspend fun loadReport(): PhotonLoadReport =
            PhotonLoadReport(
                photons = photons.values.toList(),
                unreadableFiles = emptyList(),
            )

        override suspend fun load(ref: PhotonRevisionRef): Photon? =
            photons[ref.photonId]?.takeIf { it.revision == ref.revision }

        override suspend fun latestRef(id: PhotonId): PhotonRevisionRef? =
            photons[id]?.let { PhotonRevisionRef(it.id, it.revision) }

        override suspend fun saveRevision(
            photon: Photon,
            expectedPreviousRevision: Long?,
        ): PhotonRevisionWriteResult {
            val existing = photons[photon.id]
            if (existing == null) {
                if (expectedPreviousRevision != null) {
                    return PhotonRevisionWriteResult.Conflict(
                        photon = photon,
                        previous = null,
                        reason = "missing-previous",
                    )
                }
                photons[photon.id] = photon
                return PhotonRevisionWriteResult.Created(photon)
            }
            if (existing == photon) {
                return PhotonRevisionWriteResult.Idempotent(
                    photon = photon,
                    previous = existing,
                )
            }
            return PhotonRevisionWriteResult.Conflict(
                photon = photon,
                previous = existing,
                reason = "immutable-test-repository",
            )
        }

        override suspend fun query(query: PhotonIndexQuery): List<PhotonRevisionRef> {
            val filtered = photons.values
                .asSequence()
                .filter { photon ->
                    (!query.latestOnly || true) &&
                        (query.ids.isEmpty() || photon.id in query.ids) &&
                        (query.phases.isEmpty() || photon.phase in query.phases) &&
                        (query.mimeTypes.isEmpty() || photon.mimeType in query.mimeTypes) &&
                        photon.tags.containsAll(query.allTags) &&
                        photon.tags.none { it in query.excludedTags }
                }
            val sorted = when (query.order) {
                PhotonIndexOrder.NEWEST_FIRST -> filtered.sortedByDescending { it.provenance.createdAt }
                PhotonIndexOrder.OLDEST_FIRST -> filtered.sortedBy { it.provenance.createdAt }
                PhotonIndexOrder.HIGHEST_SEMANTIC_MASS -> filtered.sortedByDescending { it.semanticMass }
                PhotonIndexOrder.HIGHEST_CONFIDENCE -> filtered.sortedByDescending { it.confidence }
                PhotonIndexOrder.IDENTITY -> filtered.sortedBy { it.id.value }
            }
            return sorted
                .take(query.limit)
                .map { PhotonRevisionRef(it.id, it.revision) }
                .toList()
        }

        override suspend fun indexReport(): PhotonIndexReport =
            PhotonIndexReport(
                formatVersion = 1,
                entryCount = photons.size,
                livePhotonCount = photons.size,
                tombstonedPhotonCount = 0,
                latestRefs = photons.values.associate { photon ->
                    photon.id to PhotonRevisionRef(photon.id, photon.revision)
                },
            )
    }
}
