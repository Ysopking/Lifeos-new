package app.lifeos.core.image

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import java.time.Instant

/** Creates a new immutable image Photon for a transformation; the source image is never overwritten. */
class TransformedImagePhotonFactory {
    fun create(
        descriptor: ImageAssetDescriptor,
        sourceImageId: PhotonId,
        goalPhotonId: PhotonId,
        operations: List<LocalImageTransformOperation>,
        confidence: Double,
        createdAt: Instant = Instant.now(),
    ): Photon {
        require(sourceImageId != goalPhotonId)
        require(operations.isNotEmpty())
        require(operations.distinct().size == operations.size)
        require(confidence in 0.0..1.0)
        val parentIds = setOf(sourceImageId, goalPhotonId)
        return Photon(
            content = descriptor.encode(),
            mimeType = ImagePhotonFactory.IMAGE_REFERENCE_MIME,
            semanticMass = 1.5,
            energy = 1.0,
            confidence = confidence,
            provenance = Provenance(
                source = "offline-image-transform",
                actor = "lifeos.image.transform",
                createdAt = createdAt,
                parentIds = parentIds,
            ),
            relations = setOf(
                PhotonRelation(sourceImageId, RelationType.TRANSFORMS, 1.0),
                PhotonRelation(sourceImageId, RelationType.DERIVED_FROM, confidence),
                PhotonRelation(goalPhotonId, RelationType.REFERENCES, confidence),
            ),
            tags = buildSet {
                add("image")
                add("result")
                add("transformed")
                add("offline")
                add("asset-ref")
                operations.forEach { operation ->
                    add("transform:${operation.name.lowercase()}")
                }
            },
        )
    }
}
