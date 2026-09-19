package app.lifeos.core.runtime.source

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.PhotonRevisionWriteResult
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RevisionedPhotonRepository
import app.lifeos.core.model.StableCognitiveIds
import app.lifeos.core.model.source.CanonicalSourceMetadata
import app.lifeos.core.runtime.sourcegraph.PhotonBackedSourceRelationshipRepository
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipWriteResult
import app.lifeos.core.runtime.sourcegraph.resolver.CanonicalSourceResolutionCoordinator
import java.time.Instant

data class CanonicalSourceImportResult(
    val sourceRef: PhotonRevisionRef,
    val metadataPhoton: Photon,
    val indexPhoton: Photon,
    val candidateRefs: List<PhotonRevisionRef>,
    val relationshipWrites: List<SourceRelationshipWriteResult>,
    val receipt: Photon,
) {
    val relationshipPhotons: List<Photon>
        get() = relationshipWrites.map { it.photon }
            .distinctBy { it.id to it.revision }
            .sortedWith(compareBy<Photon> { it.id.value }.thenBy { it.revision })
}

class CanonicalSourceImporter(
    private val photons: RevisionedPhotonRepository,
    private val metadata: SourceMetadataRepository = SourceMetadataRepository(photons),
    private val relationships: PhotonBackedSourceRelationshipRepository =
        PhotonBackedSourceRelationshipRepository(photons),
    private val index: SourceImportIndexRepository = SourceImportIndexRepository(photons),
    private val resolution: CanonicalSourceResolutionCoordinator =
        CanonicalSourceResolutionCoordinator(
            metadata = metadata,
            relationships = relationships,
        ),
) {
    suspend fun import(
        sourcePhoton: Photon,
        sourceMetadata: CanonicalSourceMetadata,
    ): CanonicalSourceImportResult {
        val sourceRef = PhotonRevisionRef(sourcePhoton.id, sourcePhoton.revision)
        val durable = requireNotNull(photons.load(sourceRef)) {
            "Canonical source import requires the exact source revision to be durable"
        }
        require(durable == sourcePhoton) {
            "Canonical source import exact source state differs from the durable revision"
        }

        index.ensureBackfilled()
        val metadataPhoton = metadata.commit(sourcePhoton, sourceMetadata)
        val metadataRecord = requireNotNull(metadata.load(sourceRef)) {
            "Canonical source metadata was not durable after commit"
        }
        require(metadataRecord.metadata == sourceMetadata)

        val indexPhoton = index.ensureIndexed(metadataPhoton, metadataRecord)
        val candidates = index.candidates(sourceRef, sourceMetadata)
        val evaluatedAt = deterministicEvaluationTime(sourcePhoton, sourceMetadata)
        val writes = mutableListOf<SourceRelationshipWriteResult>()
        for (candidate in candidates) {
            writes += resolution.resolve(sourceRef, candidate, evaluatedAt)
        }
        val canonicalWrites = writes
            .distinctBy { it.edge.edgeId }
            .sortedBy { it.edge.edgeId }

        val receipt = receipt(
            sourceRef = sourceRef,
            sourcePhoton = sourcePhoton,
            metadataPhoton = metadataPhoton,
            indexPhoton = indexPhoton,
            sourceMetadata = sourceMetadata,
            candidates = candidates,
            relationshipWrites = canonicalWrites,
            createdAt = evaluatedAt,
        )
        val durableReceipt = saveImmutable(receipt)

        return CanonicalSourceImportResult(
            sourceRef = sourceRef,
            metadataPhoton = metadataPhoton,
            indexPhoton = indexPhoton,
            candidateRefs = candidates,
            relationshipWrites = canonicalWrites,
            receipt = durableReceipt,
        )
    }

    private fun receipt(
        sourceRef: PhotonRevisionRef,
        sourcePhoton: Photon,
        metadataPhoton: Photon,
        indexPhoton: Photon,
        sourceMetadata: CanonicalSourceMetadata,
        candidates: List<PhotonRevisionRef>,
        relationshipWrites: List<SourceRelationshipWriteResult>,
        createdAt: Instant,
    ): Photon {
        val sourceRefFingerprint = SourceMetadataPhotonFactory.sourceRefFingerprint(sourceRef)
        val candidateFingerprint = StableCognitiveIds.fingerprint(
            "canonical-source-import-candidates/v1",
            *candidates.flatMap {
                listOf(it.photonId.value, it.revision.toString())
            }.toTypedArray(),
        )
        val resolutionFingerprint = StableCognitiveIds.fingerprint(
            "canonical-source-import-resolution/v1",
            *relationshipWrites.flatMap {
                listOf(it.edge.edgeId, it.edge.fingerprint)
            }.toTypedArray(),
        )
        val receiptId = PhotonId(
            "source-import-receipt-" + StableCognitiveIds.fingerprint(
                "canonical-source-import-receipt/v1",
                IMPORTER_VERSION,
                sourceRefFingerprint,
                sourceMetadata.metadataFingerprint,
                candidateFingerprint,
                resolutionFingerprint,
            )
        )
        return Photon(
            id = receiptId,
            revision = 1L,
            content = buildString {
                appendLine("schema=" + IMPORTER_VERSION)
                appendLine("source_ref=" + sourceRefFingerprint)
                appendLine("metadata=" + sourceMetadata.metadataFingerprint)
                appendLine("candidate_set=" + candidateFingerprint)
                appendLine("candidate_count=" + candidates.size)
                appendLine("relationship_count=" + relationshipWrites.size)
                append("resolution=" + resolutionFingerprint)
            },
            mimeType = RECEIPT_MIME_TYPE,
            semanticMass = 0.0,
            energy = 0.0,
            confidence = 1.0,
            provenance = Provenance(
                source = "canonical-source-import",
                actor = IMPORTER_VERSION,
                createdAt = createdAt,
                parentIds = buildSet {
                    add(sourcePhoton.id)
                    add(metadataPhoton.id)
                    add(indexPhoton.id)
                    relationshipWrites.forEach { add(it.photon.id) }
                },
            ),
            tags = setOf(
                "life-memory-management",
                RECEIPT_ROOT_TAG,
                "source-import-version:" + IMPORTER_VERSION,
                "source-import-ref:" + sourceRefFingerprint,
                "source-import-candidates:" + candidateFingerprint,
                "source-import-resolution:" + resolutionFingerprint,
            ),
        )
    }

    private suspend fun saveImmutable(photon: Photon): Photon {
        val existing = photons.load(photon.id)
        if (existing != null) {
            require(existing == photon) {
                "Canonical source import receipt identity collides with different content"
            }
            return existing
        }
        return when (val result = photons.saveRevision(photon, expectedPreviousRevision = null)) {
            is PhotonRevisionWriteResult.Created -> result.photon
            is PhotonRevisionWriteResult.Idempotent -> {
                require(result.photon == photon)
                result.photon
            }
            is PhotonRevisionWriteResult.Advanced ->
                error("Immutable canonical source import receipt unexpectedly advanced")
            is PhotonRevisionWriteResult.Conflict ->
                error("Canonical source import receipt conflict: " + result.reason)
        }
    }

    private fun deterministicEvaluationTime(
        sourcePhoton: Photon,
        metadata: CanonicalSourceMetadata,
    ): Instant = metadata.timestamps.importedAt
        ?: metadata.timestamps.observedAt
        ?: metadata.timestamps.modifiedAt
        ?: metadata.timestamps.occurredAt
        ?: metadata.timestamps.createdAt
        ?: sourcePhoton.provenance.createdAt

    companion object {
        const val IMPORTER_VERSION = "m209/v1"
        const val RECEIPT_ROOT_TAG = "source-import-receipt"
        const val RECEIPT_MIME_TYPE = "application/vnd.lifeos.source-import-receipt+text"
    }
}
