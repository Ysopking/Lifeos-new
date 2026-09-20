package app.lifeos.next.kernel

import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageCode
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
import app.lifeos.core.runtime.query.ProductivePhotonQueryService
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GoalActionCoordinatorTest {
    private val now = Instant.parse("2026-09-20T14:00:00Z")

    @Test
    fun `local knowledge action owns bounded context and derived persistence`() = runTest {
        val source = photon(
            id = "source",
            content = "Was weißt du über meine Balkonbank?",
            createdAt = now,
        )
        val memory = photon(
            id = "memory",
            content = "Meine Balkonbank ist 68 cm hoch.",
            tags = setOf("memory"),
            createdAt = now.minusSeconds(10),
        )
        val repository = InMemoryRevisionedPhotonRepository(
            listOf(source, memory),
        )
        val persisted = mutableListOf<Pair<Photon, PhotonIngressMode>>()
        val coordinator = GoalActionCoordinator(
            productivePhotonQueries = ProductivePhotonQueryService(repository),
            routeGoal = { error("route must not be used for local knowledge") },
            persistAndIngest = { photon, mode ->
                persisted += photon to mode
                PhotonSubmissionResult(
                    photon = photon,
                    processingQueued = true,
                )
            },
        )

        val result = assertIs<LocalKnowledgeExecutionResult.Produced>(
            coordinator.executeLocalKnowledge(
                goal = GoalFrame(
                    intent = IntentType.QUERY,
                    objective = "query: ${source.content}",
                    entities = emptyList(),
                    references = emptyList(),
                    constraints = emptyList(),
                    ambiguities = emptyList(),
                    confidence = 0.92,
                    language = LanguageCode.DE,
                ),
                sourcePhoton = source,
                goalPhotonId = PhotonId("goal"),
            )
        )

        assertEquals(listOf(memory.id), result.evidencePhotonIds)
        assertTrue(result.output.photon.content.contains("68 cm"))
        assertEquals(1, persisted.size)
        assertEquals(PhotonIngressMode.DERIVED, persisted.single().second)
        assertEquals(result.output.photon, persisted.single().first)
    }

    @Test
    fun `local action persistence failure remains a typed action failure`() = runTest {
        val source = photon(
            id = "source-failure",
            content = "Was weißt du über Quantenananas?",
            createdAt = now,
        )
        val repository = InMemoryRevisionedPhotonRepository(listOf(source))
        val coordinator = GoalActionCoordinator(
            productivePhotonQueries = ProductivePhotonQueryService(repository),
            routeGoal = { error("route must not be used for local knowledge") },
            persistAndIngest = { _, _ -> error("derived-persist-failed") },
        )

        val result = assertIs<LocalKnowledgeExecutionResult.Failed>(
            coordinator.executeLocalKnowledge(
                goal = GoalFrame(
                    intent = IntentType.QUERY,
                    objective = "query: ${source.content}",
                    entities = emptyList(),
                    references = emptyList(),
                    constraints = emptyList(),
                    ambiguities = emptyList(),
                    confidence = 0.92,
                    language = LanguageCode.DE,
                ),
                sourcePhoton = source,
                goalPhotonId = PhotonId("goal-failure"),
            )
        )

        assertEquals("derived-persist-failed", result.message)
    }

    private fun photon(
        id: String,
        content: String,
        tags: Set<String> = setOf("chat"),
        createdAt: Instant,
    ): Photon = Photon(
        id = PhotonId(id),
        content = content,
        provenance = Provenance(
            source = "test",
            actor = "owner",
            createdAt = createdAt,
        ),
        tags = tags,
    )

    private class InMemoryRevisionedPhotonRepository(
        initial: List<Photon>,
    ) : RevisionedPhotonRepository {
        private val photons = initial.associateByTo(linkedMapOf()) { it.id }

        override suspend fun save(photon: Photon) {
            photons[photon.id] = photon
        }

        override suspend fun loadAll(): List<Photon> = photons.values.toList()

        override suspend fun delete(id: PhotonId) {
            photons.remove(id)
        }

        override suspend fun load(id: PhotonId): Photon? = photons[id]

        override suspend fun loadReport(): PhotonLoadReport =
            PhotonLoadReport(photons.values.toList(), emptyList())

        override suspend fun load(ref: PhotonRevisionRef): Photon? =
            photons[ref.photonId]?.takeIf { it.revision == ref.revision }

        override suspend fun latestRef(id: PhotonId): PhotonRevisionRef? =
            photons[id]?.let { PhotonRevisionRef(it.id, it.revision) }

        override suspend fun saveRevision(
            photon: Photon,
            expectedPreviousRevision: Long?,
        ): PhotonRevisionWriteResult {
            val previous = photons[photon.id]
            if (previous == null) {
                if (expectedPreviousRevision != null) {
                    return PhotonRevisionWriteResult.Conflict(
                        photon,
                        null,
                        "missing-previous",
                    )
                }
                photons[photon.id] = photon
                return PhotonRevisionWriteResult.Created(photon)
            }
            if (previous == photon) {
                return PhotonRevisionWriteResult.Idempotent(photon, previous)
            }
            return PhotonRevisionWriteResult.Conflict(
                photon,
                previous,
                "immutable-test-repository",
            )
        }

        override suspend fun query(query: PhotonIndexQuery): List<PhotonRevisionRef> {
            val filtered = photons.values.asSequence().filter { photon ->
                (query.ids.isEmpty() || photon.id in query.ids) &&
                    (query.phases.isEmpty() || photon.phase in query.phases) &&
                    (query.mimeTypes.isEmpty() || photon.mimeType in query.mimeTypes) &&
                    photon.tags.containsAll(query.allTags) &&
                    (query.anyTags.isEmpty() || photon.tags.any { it in query.anyTags }) &&
                    photon.tags.none { it in query.excludedTags }
            }
            val sorted = when (query.order) {
                PhotonIndexOrder.NEWEST_FIRST ->
                    filtered.sortedByDescending { it.provenance.createdAt }
                PhotonIndexOrder.OLDEST_FIRST ->
                    filtered.sortedBy { it.provenance.createdAt }
                PhotonIndexOrder.HIGHEST_SEMANTIC_MASS ->
                    filtered.sortedByDescending { it.semanticMass }
                PhotonIndexOrder.HIGHEST_CONFIDENCE ->
                    filtered.sortedByDescending { it.confidence }
                PhotonIndexOrder.IDENTITY ->
                    filtered.sortedBy { it.id.value }
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
