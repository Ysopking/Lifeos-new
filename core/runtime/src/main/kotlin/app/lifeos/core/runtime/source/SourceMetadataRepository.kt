package app.lifeos.core.runtime.source

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.PhotonRevisionWriteResult
import app.lifeos.core.model.RevisionedPhotonRepository
import app.lifeos.core.model.source.CanonicalSourceMetadata

class SourceMetadataRepository(
    private val photons: RevisionedPhotonRepository,
) {
    suspend fun commit(
        sourcePhoton: Photon,
        metadata: CanonicalSourceMetadata,
    ): Photon {
        val sourceRef = PhotonRevisionRef(sourcePhoton.id, sourcePhoton.revision)
        check(photons.load(sourceRef) == sourcePhoton) {
            "Source metadata cannot commit before its exact source revision is durable"
        }

        val companion = SourceMetadataPhotonFactory.create(sourcePhoton, metadata)
        val existing = photons.load(companion.id)
        if (existing == null) {
            when (val result = photons.saveRevision(companion, expectedPreviousRevision = null)) {
                is PhotonRevisionWriteResult.Created -> Unit
                is PhotonRevisionWriteResult.Idempotent -> check(result.photon == companion)
                is PhotonRevisionWriteResult.Advanced,
                is PhotonRevisionWriteResult.Conflict,
                -> error("Unexpected source metadata companion write result: $result")
            }
        } else {
            check(existing == companion) {
                "Conflicting metadata for the same exact source revision"
            }
        }

        check(photons.load(companion.id) == companion) {
            "Source metadata repository returned before companion Photon became durable"
        }
        return companion
    }

    suspend fun load(sourceRef: PhotonRevisionRef): SourceMetadataRecord? {
        val photon = photons.load(SourceMetadataPhotonFactory.photonId(sourceRef)) ?: return null
        require(photon.mimeType == SourceMetadataPhotonFactory.MIME_TYPE) {
            "Source metadata identity collides with another Photon type"
        }
        require(SourceMetadataPhotonFactory.ROOT_TAG in photon.tags) {
            "Source metadata Photon is missing its canonical root tag"
        }
        val record = SourceMetadataCodec.decode(photon.content)
        require(record.sourceRef == sourceRef) {
            "Source metadata Photon is bound to a different source revision"
        }
        require(
            "source-ref:" + SourceMetadataPhotonFactory.sourceRefFingerprint(sourceRef) in photon.tags
        ) {
            "Source metadata Photon ref fingerprint mismatch"
        }
        return record
    }
}
