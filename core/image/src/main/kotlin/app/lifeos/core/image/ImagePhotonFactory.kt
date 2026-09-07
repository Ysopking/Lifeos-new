package app.lifeos.core.image

import app.lifeos.core.model.AssetId
import app.lifeos.core.model.AssetRef
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import java.time.Instant

data class ImageAssetDescriptor(
    val asset: AssetRef,
    val width: Int,
    val height: Int,
    val pixelSha256: String,
    val sceneId: String,
    val rendererId: String,
) {
    init {
        require(asset.mediaType == "image/png")
        require(width > 0 && height > 0)
        require(pixelSha256.matches(Regex("[0-9a-f]{64}")))
        requireSafe(sceneId, "sceneId")
        requireSafe(rendererId, "rendererId")
    }

    fun encode(): String = buildString {
        appendLine("lifeos-image-ref-v1")
        appendLine("assetId=${asset.id.value}")
        appendLine("mediaType=${asset.mediaType}")
        appendLine("byteCount=${asset.byteCount}")
        appendLine("assetSha256=${asset.sha256}")
        appendLine("pixelSha256=$pixelSha256")
        appendLine("width=$width")
        appendLine("height=$height")
        appendLine("sceneId=$sceneId")
        append("rendererId=$rendererId")
    }

    companion object {
        fun decode(content: String): ImageAssetDescriptor {
            val lines = content.lines()
            require(lines.firstOrNull() == "lifeos-image-ref-v1") { "Unsupported image reference" }
            val values = lines.drop(1).associate { line ->
                val separator = line.indexOf('=')
                require(separator > 0) { "Malformed image reference" }
                line.substring(0, separator) to line.substring(separator + 1)
            }
            return ImageAssetDescriptor(
                asset = AssetRef(
                    id = AssetId(required(values, "assetId")),
                    mediaType = required(values, "mediaType"),
                    byteCount = required(values, "byteCount").toLong(),
                    sha256 = required(values, "assetSha256"),
                ),
                width = required(values, "width").toInt(),
                height = required(values, "height").toInt(),
                pixelSha256 = required(values, "pixelSha256"),
                sceneId = required(values, "sceneId"),
                rendererId = required(values, "rendererId"),
            )
        }

        private fun required(values: Map<String, String>, key: String): String =
            requireNotNull(values[key]) { "Missing $key" }

        private fun requireSafe(value: String, name: String) {
            require(value.isNotBlank() && !value.contains('\n') && !value.contains('\r') && !value.contains('=')) {
                "$name contains unsupported characters"
            }
        }
    }
}

class ImagePhotonFactory {
    fun create(
        descriptor: ImageAssetDescriptor,
        parentIds: Set<PhotonId>,
        confidence: Double,
        createdAt: Instant = Instant.now(),
    ): Photon {
        require(parentIds.isNotEmpty()) { "Generated image must retain source provenance" }
        require(confidence in 0.0..1.0)
        return Photon(
            content = descriptor.encode(),
            mimeType = IMAGE_REFERENCE_MIME,
            semanticMass = 1.5,
            energy = 1.0,
            confidence = confidence,
            provenance = Provenance(
                source = "offline-procedural-image",
                actor = "lifeos.image",
                createdAt = createdAt,
                parentIds = parentIds,
            ),
            relations = parentIds.mapTo(linkedSetOf()) {
                PhotonRelation(it, RelationType.DERIVED_FROM)
            },
            tags = setOf("image", "generated", "offline", "mmsi", "asset-ref"),
        )
    }

    companion object {
        const val IMAGE_REFERENCE_MIME = "application/vnd.lifeos.image-ref+text"
    }
}
