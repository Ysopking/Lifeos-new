package app.lifeos.core.runtime.source

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import app.lifeos.core.model.StableCognitiveIds
import app.lifeos.core.model.source.CanonicalSourceMetadata
import java.time.Instant

object SourceMetadataPhotonFactory {
    const val MIME_TYPE = "application/vnd.lifeos.source-metadata+base64"
    const val ROOT_TAG = "source-metadata"

    fun photonId(sourceRef: PhotonRevisionRef): PhotonId = PhotonId(
        "source-metadata-" + StableCognitiveIds.fingerprint(
            "source-metadata-photon/v1",
            sourceRef.photonId.value,
            sourceRef.revision.toString(),
        )
    )

    fun create(
        sourcePhoton: Photon,
        metadata: CanonicalSourceMetadata,
    ): Photon {
        val sourceRef = PhotonRevisionRef(sourcePhoton.id, sourcePhoton.revision)
        val record = SourceMetadataRecord(sourceRef, metadata)
        val createdAt = metadata.timestamps.importedAt
            ?: metadata.timestamps.observedAt
            ?: sourcePhoton.provenance.createdAt

        return Photon(
            id = photonId(sourceRef),
            revision = 1L,
            content = SourceMetadataCodec.encode(record),
            mimeType = MIME_TYPE,
            semanticMass = 0.35,
            energy = 0.1,
            confidence = sourcePhoton.confidence,
            provenance = Provenance(
                source = "canonical-source-metadata",
                actor = CanonicalSourceMetadata.SCHEMA_VERSION,
                createdAt = createdAt,
                parentIds = setOf(sourcePhoton.id),
            ),
            relations = setOf(
                PhotonRelation(
                    target = sourcePhoton.id,
                    type = RelationType.REFERENCES,
                    weight = 1.0,
                )
            ),
            tags = buildSet {
                add(ROOT_TAG)
                add("source-kind:" + metadata.objectKind.name.lowercase())
                add("privacy:" + metadata.privacyZone.name.lowercase())
                add("source-provider:" + fingerprintTag(metadata.externalObject.provider.providerId))
                add("source-account:" + metadata.externalObject.account.fingerprint)
                add("source-object:" + metadata.externalObject.objectFingerprint)
                add("source-version:" + metadata.externalObject.versionFingerprint)
                add("source-ref:" + sourceRefFingerprint(sourceRef))
                metadata.conversation?.threadId?.let {
                    add("source-thread:" + fingerprintTag(it))
                }
                metadata.conversation?.conversationId?.let {
                    add("source-conversation:" + fingerprintTag(it))
                }
                metadata.document?.logicalDocumentId?.let {
                    add("source-document:" + fingerprintTag(it))
                }
                metadata.projectHint?.explicitProjectId?.let {
                    add("source-project:" + fingerprintTag(it))
                }
                add("source-metadata-fingerprint:" + metadata.metadataFingerprint)
            },
        )
    }

    fun sourceRefFingerprint(sourceRef: PhotonRevisionRef): String =
        StableCognitiveIds.fingerprint(
            "source-metadata-ref/v1",
            sourceRef.photonId.value,
            sourceRef.revision.toString(),
        )

    private fun fingerprintTag(value: String): String =
        StableCognitiveIds.fingerprint("source-metadata-tag/v1", value)
}
