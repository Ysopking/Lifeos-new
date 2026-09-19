package app.lifeos.core.runtime.sourcegraph

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonIndexOrder
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.PhotonRevisionWriteResult
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import app.lifeos.core.model.RevisionedPhotonRepository
import app.lifeos.core.model.StableCognitiveIds

sealed interface SourceRelationshipWriteResult {
    val edge: SourceRelationshipEdge
    val photon: Photon

    data class Created(
        override val edge: SourceRelationshipEdge,
        override val photon: Photon,
    ) : SourceRelationshipWriteResult

    data class Advanced(
        override val edge: SourceRelationshipEdge,
        override val photon: Photon,
        val previous: SourceRelationshipEdge,
    ) : SourceRelationshipWriteResult

    data class Idempotent(
        override val edge: SourceRelationshipEdge,
        override val photon: Photon,
    ) : SourceRelationshipWriteResult
}

class PhotonBackedSourceRelationshipRepository(
    private val photons: RevisionedPhotonRepository,
) {
    suspend fun save(edge: SourceRelationshipEdge): SourceRelationshipWriteResult {
        verifyEvidenceRefs(edge)
        val photonId = PhotonId(edge.edgeId)
        val currentPhoton = photons.load(photonId)
        val currentEdge = currentPhoton?.let(::decodePhoton)

        if (currentEdge != null && currentEdge.fingerprint == edge.fingerprint) {
            return SourceRelationshipWriteResult.Idempotent(edge, currentPhoton)
        }
        if (currentEdge != null) {
            require(currentEdge.source == edge.source)
            require(currentEdge.target == edge.target)
            require(currentEdge.type == edge.type)
            require(currentEdge.createdAt == edge.createdAt) {
                "Relationship edge creation instant is immutable"
            }
            require(!edge.lastEvaluatedAt.isBefore(currentEdge.lastEvaluatedAt)) {
                "Relationship edge evaluation cannot move backwards"
            }
        }

        val nextRevision = (currentPhoton?.revision ?: 0L) + 1L
        val nextPhoton = encodePhoton(edge, nextRevision)
        val result = photons.saveRevision(
            nextPhoton,
            expectedPreviousRevision = currentPhoton?.revision,
        )
        when (result) {
            is PhotonRevisionWriteResult.Created -> {
                check(currentPhoton == null)
            }
            is PhotonRevisionWriteResult.Advanced -> {
                check(currentPhoton != null)
            }
            is PhotonRevisionWriteResult.Idempotent -> {
                check(result.photon == nextPhoton)
            }
            is PhotonRevisionWriteResult.Conflict -> {
                error("Source relationship CAS conflict: " + result.reason)
            }
        }
        check(photons.load(PhotonRevisionRef(nextPhoton.id, nextPhoton.revision)) == nextPhoton) {
            "Relationship repository returned before exact revision became durable"
        }

        return if (currentEdge == null) {
            SourceRelationshipWriteResult.Created(edge, nextPhoton)
        } else {
            SourceRelationshipWriteResult.Advanced(edge, nextPhoton, currentEdge)
        }
    }

    suspend fun load(edgeId: String): SourceRelationshipEdge? {
        val photon = photons.load(PhotonId(edgeId)) ?: return null
        return decodePhoton(photon)
    }

    suspend fun load(
        source: PhotonRevisionRef,
        target: PhotonRevisionRef,
        type: SourceRelationshipType,
    ): SourceRelationshipEdge? =
        load(relationshipEdgeId(source, target, type))

    suspend fun relationshipsFor(
        ref: PhotonRevisionRef,
        limit: Int = PhotonIndexQuery.DEFAULT_PAGE_LIMIT,
    ): List<SourceRelationshipEdge> {
        require(limit in 1..PhotonIndexQuery.HARD_PAGE_LIMIT)
        val tag = refTag(ref)
        return photons.query(
            PhotonIndexQuery(
                allTags = setOf(ROOT_TAG, tag),
                latestOnly = true,
                order = PhotonIndexOrder.IDENTITY,
                limit = limit,
            )
        ).map { exact ->
            decodePhoton(
                requireNotNull(photons.load(exact)) {
                    "Relationship index references a missing exact revision"
                }
            )
        }.sortedBy { it.edgeId }
    }

    private suspend fun verifyEvidenceRefs(edge: SourceRelationshipEdge) {
        val required = buildSet {
            add(edge.source)
            add(edge.target)
            (edge.positiveEvidence + edge.negativeEvidence).forEach { evidence ->
                add(evidence.sourceRef)
                add(evidence.lineageRoot)
            }
        }
        required.forEach { ref ->
            requireNotNull(photons.load(ref)) {
                "Relationship evidence references missing Photon revision: " +
                    ref.photonId.value + "@" + ref.revision
            }
        }
    }

    private fun encodePhoton(
        edge: SourceRelationshipEdge,
        revision: Long,
    ): Photon {
        val parents = buildSet {
            add(edge.source.photonId)
            add(edge.target.photonId)
            (edge.positiveEvidence + edge.negativeEvidence).forEach { evidence ->
                add(evidence.sourceRef.photonId)
                add(evidence.lineageRoot.photonId)
            }
        }
        return Photon(
            id = PhotonId(edge.edgeId),
            revision = revision,
            content = SourceRelationshipCodec.encode(edge),
            mimeType = MIME_TYPE,
            semanticMass = 0.4,
            energy = 0.1,
            confidence = edge.confidence,
            provenance = Provenance(
                source = "source-relationship-ledger",
                actor = edge.resolverId,
                createdAt = edge.lastEvaluatedAt,
                parentIds = parents,
            ),
            relations = setOf(
                PhotonRelation(edge.source.photonId, RelationType.REFERENCES, 1.0),
                PhotonRelation(edge.target.photonId, RelationType.REFERENCES, 1.0),
            ),
            tags = setOf(
                ROOT_TAG,
                "relationship-type:" + edge.type.name.lowercase(),
                "relationship-family:" + edge.type.family.name.lowercase(),
                "relationship-state:" + edge.state.name.lowercase(),
                refTag(edge.source),
                refTag(edge.target),
                "relationship-resolver:" + StableCognitiveIds.fingerprint(
                    "relationship-resolver-tag/v1",
                    edge.resolverId,
                    edge.resolverVersion,
                ),
            ),
        )
    }

    private fun decodePhoton(photon: Photon): SourceRelationshipEdge {
        require(photon.mimeType == MIME_TYPE) {
            "Relationship Photon identity collides with another MIME type"
        }
        require(ROOT_TAG in photon.tags) { "Relationship Photon root tag missing" }
        val edge = SourceRelationshipCodec.decode(photon.content)
        require(photon.id.value == edge.edgeId) { "Relationship Photon id mismatch" }
        require(refTag(edge.source) in photon.tags)
        require(refTag(edge.target) in photon.tags)
        return edge
    }

    private fun refTag(ref: PhotonRevisionRef): String =
        "relationship-ref:" + StableCognitiveIds.fingerprint(
            "relationship-ref/v1",
            ref.photonId.value,
            ref.revision.toString(),
        )

    companion object {
        const val MIME_TYPE = "application/vnd.lifeos.source-relationship+base64"
        const val ROOT_TAG = "source-relationship"
    }
}
