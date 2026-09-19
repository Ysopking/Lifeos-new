package app.lifeos.core.runtime.sourcegraph

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
import app.lifeos.core.model.matches
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PhotonBackedSourceRelationshipRepositoryTest {
    private val at = Instant.parse("2026-09-19T12:30:00Z")

    @Test
    fun exactRevisionBoundEdgeCreatesAdvancesAndReloads() = runTest {
        val photons = MemoryRepository()
        val source = photon("source")
        val target = photon("target")
        val evidence = photon("evidence")
        photons.save(source)
        photons.save(target)
        photons.save(evidence)
        val repository = PhotonBackedSourceRelationshipRepository(photons)
        val first = edge(source, target, evidence, SourceRelationshipState.CANDIDATE, at)

        val created = assertIs<SourceRelationshipWriteResult.Created>(repository.save(first))
        assertEquals(1L, created.photon.revision)
        assertEquals(first, repository.load(first.edgeId))

        val confirmed = first.copy(
            state = SourceRelationshipState.CONFIRMED,
            confidence = 0.98,
            lastEvaluatedAt = at.plusSeconds(1),
        )
        val advanced = assertIs<SourceRelationshipWriteResult.Advanced>(
            repository.save(confirmed)
        )

        assertEquals(2L, advanced.photon.revision)
        assertEquals(first, advanced.previous)
        assertEquals(confirmed, repository.load(first.edgeId))
        assertEquals(listOf(confirmed), repository.relationshipsFor(PhotonRevisionRef(source.id, 1)))
    }

    @Test
    fun repeatedExactEdgeIsIdempotent() = runTest {
        val photons = MemoryRepository()
        val source = photon("source")
        val target = photon("target")
        val evidence = photon("evidence")
        listOf(source, target, evidence).forEach { photons.save(it) }
        val repository = PhotonBackedSourceRelationshipRepository(photons)
        val edge = edge(source, target, evidence, SourceRelationshipState.CONFIRMED, at)

        repository.save(edge)
        val second = assertIs<SourceRelationshipWriteResult.Idempotent>(repository.save(edge))

        assertEquals(1L, second.photon.revision)
    }

    @Test
    fun missingExactEvidenceRevisionFailsClosed() = runTest {
        val photons = MemoryRepository()
        val source = photon("source")
        val target = photon("target")
        photons.save(source)
        photons.save(target)
        val missing = photon("missing")
        val repository = PhotonBackedSourceRelationshipRepository(photons)

        assertFailsWith<IllegalArgumentException> {
            repository.save(edge(source, target, missing, SourceRelationshipState.CANDIDATE, at))
        }
    }

    @Test
    fun codecRoundTripPreservesFingerprint() {
        val source = photon("source")
        val target = photon("target")
        val evidence = photon("evidence")
        val edge = edge(source, target, evidence, SourceRelationshipState.MERGE_ELIGIBLE, at)

        val decoded = SourceRelationshipCodec.decode(SourceRelationshipCodec.encode(edge))

        assertEquals(edge, decoded)
        assertEquals(edge.fingerprint, decoded.fingerprint)
    }

    private fun edge(
        source: Photon,
        target: Photon,
        evidencePhoton: Photon,
        state: SourceRelationshipState,
        evaluatedAt: Instant,
    ): SourceRelationshipEdge {
        val evidence = SourceRelationshipEvidence.create(
            family = RelationshipEvidenceFamily.OPERATIONAL,
            kind = RelationshipEvidenceKind.REPOSITORY_ID,
            strength = EvidenceStrength.STRONG,
            polarity = EvidencePolarity.POSITIVE,
            sourceRef = PhotonRevisionRef(evidencePhoton.id, evidencePhoton.revision),
            confidence = 0.91,
            explanation = "repository identity",
        )
        return SourceRelationshipEdge(
            source = PhotonRevisionRef(source.id, source.revision),
            target = PhotonRevisionRef(target.id, target.revision),
            type = SourceRelationshipType.SAME_PROJECT,
            state = state,
            confidence = if (state == SourceRelationshipState.CONFIRMED) 0.98 else 0.91,
            positiveEvidence = listOf(evidence),
            negativeEvidence = emptyList(),
            blockers = emptyList(),
            resolverId = "test-resolver",
            resolverVersion = "1",
            createdAt = at,
            lastEvaluatedAt = evaluatedAt,
        )
    }

    private fun photon(id: String): Photon = Photon(
        id = PhotonId(id),
        content = id,
        provenance = Provenance("test", "test", at),
    )

    private class MemoryRepository : RevisionedPhotonRepository {
        private val values = linkedMapOf<PhotonRevisionRef, Photon>()

        override suspend fun save(photon: Photon) {
            val current = load(photon.id)
            when (val result = saveRevision(photon, current?.revision)) {
                is PhotonRevisionWriteResult.Created,
                is PhotonRevisionWriteResult.Advanced,
                is PhotonRevisionWriteResult.Idempotent -> Unit
                is PhotonRevisionWriteResult.Conflict -> error(result.reason)
            }
        }

        override suspend fun saveRevision(
            photon: Photon,
            expectedPreviousRevision: Long?,
        ): PhotonRevisionWriteResult {
            val current = load(photon.id)
            if (current != null && current.revision == photon.revision) {
                return if (current == photon) {
                    PhotonRevisionWriteResult.Idempotent(photon, current)
                } else {
                    PhotonRevisionWriteResult.Conflict(photon, current, "same revision differs")
                }
            }
            if (current == null) {
                if (expectedPreviousRevision != null || photon.revision != 1L) {
                    return PhotonRevisionWriteResult.Conflict(photon, null, "invalid first revision")
                }
                values[PhotonRevisionRef(photon.id, photon.revision)] = photon
                return PhotonRevisionWriteResult.Created(photon)
            }
            if (
                expectedPreviousRevision != current.revision ||
                photon.revision != current.revision + 1L
            ) {
                return PhotonRevisionWriteResult.Conflict(photon, current, "stale revision")
            }
            values[PhotonRevisionRef(photon.id, photon.revision)] = photon
            return PhotonRevisionWriteResult.Advanced(photon, current)
        }

        override suspend fun load(id: PhotonId): Photon? =
            values.values.filter { it.id == id }.maxByOrNull { it.revision }

        override suspend fun load(ref: PhotonRevisionRef): Photon? = values[ref]

        override suspend fun latestRef(id: PhotonId): PhotonRevisionRef? =
            load(id)?.let { PhotonRevisionRef(it.id, it.revision) }

        override suspend fun query(query: PhotonIndexQuery): List<PhotonRevisionRef> {
            val latest = values.values.groupBy { it.id }.map { (_, revisions) -> revisions.maxBy { it.revision } }
            val source = if (query.latestOnly) latest else values.values.toList()
            val ordered = source
                .map(::indexEntry)
                .filter { it.matches(query) }
                .let { entries ->
                    when (query.order) {
                        PhotonIndexOrder.IDENTITY -> entries.sortedWith(
                            compareBy({ it.ref.photonId.value }, { it.ref.revision })
                        )
                        PhotonIndexOrder.NEWEST_FIRST -> entries.sortedByDescending { it.createdAt }
                        PhotonIndexOrder.OLDEST_FIRST -> entries.sortedBy { it.createdAt }
                        PhotonIndexOrder.HIGHEST_SEMANTIC_MASS -> entries.sortedByDescending { it.semanticMass }
                        PhotonIndexOrder.HIGHEST_CONFIDENCE -> entries.sortedByDescending { it.confidence }
                    }
                }
            val start = query.after?.let { cursor ->
                ordered.indexOfFirst { it.ref == cursor.lastRef }.also { require(it >= 0) } + 1
            } ?: 0
            return ordered.drop(start).take(query.limit).map { it.ref }
        }

        override suspend fun indexReport(): PhotonIndexReport {
            val latest = values.values.groupBy { it.id }.mapValues { (_, revisions) -> revisions.maxBy { it.revision } }
            return PhotonIndexReport(
                formatVersion = 2,
                entryCount = values.size,
                livePhotonCount = latest.size,
                tombstonedPhotonCount = 0,
                latestRefs = latest.mapValues { (_, photon) -> PhotonRevisionRef(photon.id, photon.revision) },
            )
        }

        override suspend fun loadReport(): PhotonLoadReport =
            PhotonLoadReport(
                photons = values.values.groupBy { it.id }.values.map { it.maxBy { p -> p.revision } },
                unreadableFiles = emptyList(),
            )

        override suspend fun loadAll(): List<Photon> = loadReport().photons

        override suspend fun delete(id: PhotonId) {
            values.keys.filter { it.photonId == id }.forEach(values::remove)
        }

        private fun indexEntry(photon: Photon) = app.lifeos.core.model.PhotonIndexEntry(
            ref = PhotonRevisionRef(photon.id, photon.revision),
            createdAt = photon.provenance.createdAt,
            phase = photon.phase,
            mimeType = photon.mimeType,
            tags = photon.tags,
            semanticMass = photon.semanticMass,
            confidence = photon.confidence,
            contentFingerprint = "0".repeat(64),
            latest = true,
        )
    }
}
