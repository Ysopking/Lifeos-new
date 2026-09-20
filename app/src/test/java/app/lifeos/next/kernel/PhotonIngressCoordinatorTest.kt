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
import app.lifeos.core.runtime.cognition.CognitivePriority
import app.lifeos.core.runtime.cognition.CognitiveSubmissionResult
import app.lifeos.core.runtime.cognition.CognitiveWorkBudget
import app.lifeos.core.runtime.cognition.PhotonDelta
import app.lifeos.core.runtime.cognition.PhotonDeltaType
import app.lifeos.core.runtime.cognition.SalienceVector
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhotonIngressCoordinatorTest {
    private val at = Instant.parse("2026-09-20T12:00:00Z")
    private val budget = CognitiveWorkBudget(
        maxDurationMs = 30_000,
        maxModuleInvocations = 16,
        maxNewPhotons = 16,
        maxNetworkCalls = 0,
    )

    @Test
    fun `origin persistence creates durable cognition work with exact revision delta`() = runTest {
        val repository = InMemoryRevisionedPhotonRepository()
        val observed = mutableListOf<Photon>()
        val submissions = mutableListOf<CapturedSubmission>()
        val coordinator = coordinator(repository, observed, submissions)
        val first = photon(revision = 1, confidence = 0.6)

        val created = coordinator.persistAndIngest(first)

        assertTrue(created.processingQueued)
        assertNull(created.processingFailure)
        assertEquals(first, repository.load(first.id))
        assertEquals(listOf(first), observed)
        assertEquals(1, submissions.size)
        assertEquals(PhotonDeltaType.CREATED, submissions.single().delta.type)
        assertNull(submissions.single().delta.revisionBefore)
        assertEquals(1L, submissions.single().delta.revisionAfter)

        val second = first.copy(
            revision = 2,
            content = "updated",
            confidence = 0.9,
        )
        val advanced = coordinator.persistAndIngest(second)

        assertTrue(advanced.processingQueued)
        assertEquals(second, repository.load(second.id))
        assertEquals(listOf(first, second), observed)
        assertEquals(2, submissions.size)
        val update = submissions.last()
        assertEquals(PhotonDeltaType.UPDATED, update.delta.type)
        assertEquals(1L, update.delta.revisionBefore)
        assertEquals(2L, update.delta.revisionAfter)
        assertEquals(0.3, update.salience.confidenceImpact, absoluteTolerance = 0.000001)
        assertEquals(CognitivePriority.USER_BLOCKING, update.priority)
        assertEquals(setOf("Gedankenmatrix"), update.targetModules)
        assertEquals(budget, update.budget)
    }

    @Test
    fun `fast persistence updates durable state without cognition admission`() = runTest {
        val repository = InMemoryRevisionedPhotonRepository()
        val observed = mutableListOf<Photon>()
        val submissions = mutableListOf<CapturedSubmission>()
        val coordinator = coordinator(repository, observed, submissions)
        val source = photon(revision = 1, confidence = 1.0)

        val result = coordinator.persistWithoutCognition(
            source,
            PhotonIngressMode.ORIGIN,
        )

        assertFalse(result.processingQueued)
        assertNull(result.processingFailure)
        assertEquals(source, repository.load(source.id))
        assertEquals(listOf(source), observed)
        assertTrue(submissions.isEmpty())
    }

    @Test
    fun `non durable cognition admission stays persisted but reports failure`() = runTest {
        val repository = InMemoryRevisionedPhotonRepository()
        val observed = mutableListOf<Photon>()
        val source = photon(revision = 1, confidence = 1.0)
        val coordinator = PhotonIngressCoordinator(
            photonStore = repository,
            liveSubmissionBudget = budget,
            submitCognition = { _, _, _, _, _ ->
                CognitiveSubmissionResult(
                    journalOffset = 1,
                    workId = "work-1",
                    accepted = true,
                    durableTaskId = null,
                )
            },
            onPhotonPersisted = observed::add,
        )

        val result = coordinator.persistAndIngest(source)

        assertFalse(result.processingQueued)
        assertEquals("Cognitive work was not durabilized", result.processingFailure)
        assertEquals(source, repository.load(source.id))
        assertEquals(listOf(source), observed)
    }

    private fun coordinator(
        repository: InMemoryRevisionedPhotonRepository,
        observed: MutableList<Photon>,
        submissions: MutableList<CapturedSubmission>,
    ): PhotonIngressCoordinator = PhotonIngressCoordinator(
        photonStore = repository,
        liveSubmissionBudget = budget,
        submitCognition = { delta, priority, salience, targetModules, workBudget ->
            submissions += CapturedSubmission(
                delta,
                priority,
                salience,
                targetModules,
                workBudget,
            )
            CognitiveSubmissionResult(
                journalOffset = submissions.size.toLong(),
                workId = "work-${submissions.size}",
                accepted = true,
                durableTaskId = "task-${submissions.size}",
            )
        },
        onPhotonPersisted = observed::add,
    )

    private fun photon(
        revision: Long,
        confidence: Double,
    ): Photon = Photon(
        id = PhotonId("ingress-test"),
        revision = revision,
        content = "payload-$revision",
        semanticMass = 0.8,
        confidence = confidence,
        provenance = Provenance(
            source = "test",
            actor = "owner",
            createdAt = at.plusSeconds(revision),
        ),
        tags = setOf("chat", "chat:user"),
    )

    private data class CapturedSubmission(
        val delta: PhotonDelta,
        val priority: CognitivePriority,
        val salience: SalienceVector,
        val targetModules: Set<String>,
        val budget: CognitiveWorkBudget,
    )

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
                if (expectedPreviousRevision != null || photon.revision != 1L) {
                    return PhotonRevisionWriteResult.Conflict(
                        photon,
                        null,
                        "invalid-create",
                    )
                }
                photons[photon.id] = photon
                return PhotonRevisionWriteResult.Created(photon)
            }
            if (previous == photon) {
                return PhotonRevisionWriteResult.Idempotent(photon, previous)
            }
            if (
                expectedPreviousRevision != previous.revision ||
                photon.revision != previous.revision + 1L
            ) {
                return PhotonRevisionWriteResult.Conflict(
                    photon,
                    previous,
                    "invalid-advance",
                )
            }
            photons[photon.id] = photon
            return PhotonRevisionWriteResult.Advanced(photon, previous)
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
                PhotonIndexOrder.NEWEST_FIRST -> filtered.sortedByDescending { it.provenance.createdAt }
                PhotonIndexOrder.OLDEST_FIRST -> filtered.sortedBy { it.provenance.createdAt }
                PhotonIndexOrder.HIGHEST_SEMANTIC_MASS -> filtered.sortedByDescending { it.semanticMass }
                PhotonIndexOrder.HIGHEST_CONFIDENCE -> filtered.sortedByDescending { it.confidence }
                PhotonIndexOrder.IDENTITY -> filtered.sortedBy { it.id.value }
            }
            return sorted.take(query.limit)
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
