package app.lifeos.core.runtime.sourcegraph.resolver

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
import app.lifeos.core.model.source.CanonicalSourceMetadata
import app.lifeos.core.model.source.SourceAccountRef
import app.lifeos.core.model.source.SourceExternalObjectRef
import app.lifeos.core.model.source.SourceMetadataOrigin
import app.lifeos.core.model.source.SourceObjectKind
import app.lifeos.core.model.source.SourcePrivacyZone
import app.lifeos.core.model.source.SourceProviderRef
import app.lifeos.core.runtime.source.SourceMetadataRepository
import app.lifeos.core.runtime.sourcegraph.PhotonBackedSourceRelationshipRepository
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipState
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipType
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipWriteResult
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EntityResolutionCoordinatorTest {
    private val at = Instant.parse("2026-09-19T14:00:00Z")

    @Test
    fun exactObjectIdentityPersistsConfirmedEdgeAndRepeatedResolutionIsIdempotent() = runTest {
        val photons = MemoryRepository()
        val left = source("left")
        val right = source("right")
        photons.save(left)
        photons.save(right)

        val metadata = SourceMetadataRepository(photons)
        metadata.commit(left, metadata("mail", "account", "message-42", "v1"))
        metadata.commit(right, metadata("mail", "account", "message-42", "v2"))

        val relationshipRepository = PhotonBackedSourceRelationshipRepository(photons)
        val coordinator = EntityResolutionCoordinator(
            metadata = metadata,
            relationships = relationshipRepository,
        )

        val first = coordinator.resolve(
            PhotonRevisionRef(left.id, left.revision),
            PhotonRevisionRef(right.id, right.revision),
            at,
        )
        val sameObject = first.single { it.edge.type == SourceRelationshipType.SAME_OBJECT }

        assertEquals(SourceRelationshipState.CONFIRMED, sameObject.edge.state)
        assertIs<SourceRelationshipWriteResult.Created>(sameObject)

        val second = coordinator.resolve(
            PhotonRevisionRef(right.id, right.revision),
            PhotonRevisionRef(left.id, left.revision),
            at.plusSeconds(1),
        )
        val repeated = second.single { it.edge.type == SourceRelationshipType.SAME_OBJECT }

        assertIs<SourceRelationshipWriteResult.Idempotent>(repeated)
        assertEquals(sameObject.edge, repeated.edge)
    }

    private fun source(id: String): Photon = Photon(
        id = PhotonId(id),
        content = id,
        provenance = Provenance("test", "user", at),
    )

    private fun metadata(
        provider: String,
        account: String,
        externalId: String,
        version: String,
    ): CanonicalSourceMetadata = CanonicalSourceMetadata(
        objectKind = SourceObjectKind.MESSAGE,
        origin = SourceMetadataOrigin.CONNECTOR,
        privacyZone = SourcePrivacyZone.PRIVATE,
        externalObject = SourceExternalObjectRef(
            provider = SourceProviderRef(provider),
            account = SourceAccountRef(provider, account),
            objectKind = SourceObjectKind.MESSAGE,
            externalId = externalId,
            externalVersion = version,
        ),
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
            val latest = values.values.groupBy { it.id }.map { (_, revisions) ->
                revisions.maxBy { it.revision }
            }
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
            val latest = values.values.groupBy { it.id }.mapValues { (_, revisions) ->
                revisions.maxBy { it.revision }
            }
            return PhotonIndexReport(
                formatVersion = 2,
                entryCount = values.size,
                livePhotonCount = latest.size,
                tombstonedPhotonCount = 0,
                latestRefs = latest.mapValues { (_, photon) ->
                    PhotonRevisionRef(photon.id, photon.revision)
                },
            )
        }

        override suspend fun loadReport(): PhotonLoadReport =
            PhotonLoadReport(
                photons = values.values.groupBy { it.id }.values.map { it.maxBy { photon -> photon.revision } },
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
