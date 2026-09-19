package app.lifeos.core.runtime.life

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.StableCognitiveIds
import app.lifeos.core.model.source.CanonicalSourceMetadata
import app.lifeos.core.model.source.SourceObjectKind
import app.lifeos.core.runtime.source.SourceMetadataCodec
import app.lifeos.core.runtime.source.SourceMetadataPhotonFactory
import app.lifeos.core.runtime.sourcegraph.PhotonBackedSourceRelationshipRepository
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipCodec
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipState

internal data class CanonicalLifeGraphProjectionResult(
    val entities: List<LifeEntity>,
    val relationships: List<LifeGraphRelationship>,
)

internal object CanonicalLifeGraphProjection {
    fun project(latestPhotons: Collection<Photon>): CanonicalLifeGraphProjectionResult {
        val metadataByRef = latestPhotons
            .asSequence()
            .filter {
                it.mimeType == SourceMetadataPhotonFactory.MIME_TYPE &&
                    SourceMetadataPhotonFactory.ROOT_TAG in it.tags
            }
            .map { photon ->
                val record = SourceMetadataCodec.decode(photon.content)
                require(photon.id == SourceMetadataPhotonFactory.photonId(record.sourceRef)) {
                    "Source metadata Photon identity does not match its exact source revision"
                }
                record.sourceRef to (photon to record.metadata)
            }
            .toMap()

        val relationshipPhotons = latestPhotons
            .asSequence()
            .filter {
                it.mimeType == PhotonBackedSourceRelationshipRepository.MIME_TYPE &&
                    PhotonBackedSourceRelationshipRepository.ROOT_TAG in it.tags
            }
            .map { photon ->
                val edge = SourceRelationshipCodec.decode(photon.content)
                require(photon.id.value == edge.edgeId) {
                    "Relationship Photon identity does not match decoded edge"
                }
                photon to edge
            }
            .filter { (_, edge) ->
                edge.state == SourceRelationshipState.CONFIRMED ||
                    edge.state == SourceRelationshipState.MERGE_ELIGIBLE
            }
            .sortedBy { (_, edge) -> edge.edgeId }
            .toList()

        val referencedRefs = buildSet {
            addAll(metadataByRef.keys)
            relationshipPhotons.forEach { (_, edge) ->
                add(edge.source)
                add(edge.target)
            }
        }

        val entityByRef = referencedRefs
            .sortedWith(compareBy<PhotonRevisionRef> { it.photonId.value }.thenBy { it.revision })
            .associateWith { ref ->
                val metadata = metadataByRef[ref]
                if (metadata == null) {
                    fallbackEntity(ref)
                } else {
                    metadataEntity(ref, metadata.first, metadata.second)
                }
            }

        val relationships = relationshipPhotons.map { (photon, edge) ->
            val from = requireNotNull(entityByRef[edge.source])
            val to = requireNotNull(entityByRef[edge.target])
            val refs = buildSet {
                add(edge.source)
                add(edge.target)
                (edge.positiveEvidence + edge.negativeEvidence).forEach { evidence ->
                    add(evidence.sourceRef)
                    add(evidence.lineageRoot)
                }
            }
            val sourcePhotonIds = buildSet {
                add(photon.id)
                refs.forEach { add(it.photonId) }
            }
            LifeGraphRelationship(
                id = "relation-" + StableCognitiveIds.fingerprint(
                    "life-source-relationship-projection/v1",
                    edge.edgeId,
                ),
                fromEntityId = from.id,
                toEntityId = to.id,
                type = edge.type.name,
                sourcePhotonIds = sourcePhotonIds,
                confidence = edge.confidence,
                canonicalType = edge.type,
                canonicalState = edge.state,
                sourceRevisionRefs = refs,
                evidenceFingerprint = edge.fingerprint,
            )
        }

        return CanonicalLifeGraphProjectionResult(
            entities = entityByRef.values.sortedBy { it.id },
            relationships = relationships,
        )
    }

    private fun metadataEntity(
        ref: PhotonRevisionRef,
        metadataPhoton: Photon,
        metadata: CanonicalSourceMetadata,
    ): LifeEntity = LifeEntity(
        id = sourceEntityId(ref),
        type = lifeEntityType(metadata.objectKind),
        label = sourceLabel(metadata),
        sourcePhotonIds = setOf(ref.photonId, metadataPhoton.id),
        confidence = metadataPhoton.confidence,
    )

    private fun fallbackEntity(ref: PhotonRevisionRef): LifeEntity = LifeEntity(
        id = sourceEntityId(ref),
        type = LifeEntityType.ARTIFACT,
        label = "source-" + StableCognitiveIds.fingerprint(
            "life-source-label/v1",
            ref.photonId.value,
            ref.revision.toString(),
        ).take(12),
        sourcePhotonIds = setOf(ref.photonId),
        confidence = 1.0,
    )

    private fun sourceEntityId(ref: PhotonRevisionRef): String =
        "source-entity-" + StableCognitiveIds.fingerprint(
            "life-source-entity/v1",
            ref.photonId.value,
            ref.revision.toString(),
        )

    private fun sourceLabel(metadata: CanonicalSourceMetadata): String = when (metadata.objectKind) {
        SourceObjectKind.CONTACT ->
            metadata.actor?.displayName
                ?: metadata.actor?.address
                ?: stableObjectLabel(metadata)

        SourceObjectKind.PROJECT ->
            metadata.projectHint?.projectName
                ?: stableObjectLabel(metadata)

        SourceObjectKind.CONVERSATION ->
            metadata.conversation?.conversationId
                ?: metadata.conversation?.threadId
                ?: stableObjectLabel(metadata)

        SourceObjectKind.DOCUMENT ->
            metadata.document?.title
                ?: metadata.file?.name
                ?: stableObjectLabel(metadata)

        SourceObjectKind.FILE,
        SourceObjectKind.IMAGE,
        SourceObjectKind.AUDIO,
        SourceObjectKind.VIDEO,
        -> metadata.file?.name ?: stableObjectLabel(metadata)

        else -> stableObjectLabel(metadata)
    }

    private fun stableObjectLabel(metadata: CanonicalSourceMetadata): String =
        metadata.objectKind.name.lowercase() + "-" +
            metadata.externalObject.objectFingerprint.take(12)

    private fun lifeEntityType(kind: SourceObjectKind): LifeEntityType = when (kind) {
        SourceObjectKind.CONTACT -> LifeEntityType.PERSON
        SourceObjectKind.PROJECT -> LifeEntityType.PROJECT
        SourceObjectKind.CONVERSATION -> LifeEntityType.CONVERSATION
        SourceObjectKind.CALENDAR_EVENT -> LifeEntityType.EVENT
        SourceObjectKind.DOCUMENT,
        SourceObjectKind.FILE,
        -> LifeEntityType.DOCUMENT

        SourceObjectKind.REPOSITORY,
        SourceObjectKind.COMMIT,
        SourceObjectKind.ISSUE,
        SourceObjectKind.PULL_REQUEST,
        -> LifeEntityType.REPOSITORY

        SourceObjectKind.TASK -> LifeEntityType.TASK
        SourceObjectKind.MESSAGE,
        SourceObjectKind.EMAIL,
        SourceObjectKind.IMAGE,
        SourceObjectKind.AUDIO,
        SourceObjectKind.VIDEO,
        SourceObjectKind.ARTIFACT,
        SourceObjectKind.OTHER,
        -> LifeEntityType.ARTIFACT
    }
}
