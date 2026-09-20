package app.lifeos.next.kernel

import app.lifeos.core.image.DeterministicPngEncoder
import app.lifeos.core.image.ImageAssetDescriptor
import app.lifeos.core.image.ImagePhotonFactory
import app.lifeos.core.language.GoalFrame
import app.lifeos.core.model.BinaryAssetStore
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.scene.SceneGraphPhotonFactory
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.CancellationException

internal class ImageActionCoordinator(
    private val proceduralImageGenerator: ProceduralImageGenerationEngine,
    private val pngEncoder: DeterministicPngEncoder,
    private val imageAssets: BinaryAssetStore,
    private val imagePhotonFactory: ImagePhotonFactory,
    private val sceneGraphPhotonFactory: SceneGraphPhotonFactory,
    private val ownerReviewPending: String,
) {
    suspend fun loadImageAsset(photon: Photon): ByteArray? {
        if (photon.mimeType != ImagePhotonFactory.IMAGE_REFERENCE_MIME) return null
        val descriptor = runCatching {
            ImageAssetDescriptor.decode(photon.content)
        }.getOrNull() ?: return null
        return imageAssets.load(descriptor.asset)
    }

    suspend fun generateImage(
        goal: GoalFrame,
        sourcePhotonId: PhotonId,
        goalPhotonId: PhotonId,
        referenceInstant: Instant,
    ): ImageGenerationResult {
        return try {
            when (val rendered = proceduralImageGenerator.render(goal, referenceInstant)) {
                is ProceduralImageRenderResult.Blocked ->
                    ImageGenerationResult.Blocked(rendered.reasons)

                is ProceduralImageRenderResult.Rendered -> {
                    val createdAt = Instant.now()
                    val sceneGraphPhoton = sceneGraphPhotonFactory.create(
                        graph = rendered.graph,
                        goalPhotonId = goalPhotonId,
                        createdAt = createdAt,
                    )
                    val sceneSubmission = PhotonSubmissionResult(
                        photon = sceneGraphPhoton.photon,
                        processingQueued = false,
                        processingFailure = ownerReviewPending,
                    )
                    val pngBytes = pngEncoder.encode(rendered.image)
                    val asset = imageAssets.save(pngBytes, "image/png")
                    try {
                        val descriptor = ImageAssetDescriptor(
                            asset = asset,
                            width = rendered.image.width,
                            height = rendered.image.height,
                            pixelSha256 = sha256(rendered.image.copyRgba()),
                            sceneId = rendered.graph.sceneId,
                            rendererId = rendered.rendererId,
                        )
                        val imagePhoton = imagePhotonFactory.create(
                            descriptor = descriptor,
                            parentIds = setOf(
                                sourcePhotonId,
                                goalPhotonId,
                                sceneGraphPhoton.photon.id,
                            ),
                            confidence = rendered.graph.confidence,
                            createdAt = createdAt,
                        )
                        val imageSubmission = PhotonSubmissionResult(
                            photon = imagePhoton,
                            processingQueued = false,
                            processingFailure = ownerReviewPending,
                        )
                        ImageGenerationResult.Generated(
                            GeneratedImageResult(
                                scene = sceneSubmission,
                                image = imageSubmission,
                                descriptor = descriptor,
                                rendererId = rendered.rendererId,
                            ),
                        )
                    } catch (cancelled: CancellationException) {
                        runCatching { imageAssets.delete(asset.id) }
                        throw cancelled
                    } catch (error: Exception) {
                        runCatching { imageAssets.delete(asset.id) }
                        ImageGenerationResult.Failed(
                            error.message ?: error::class.simpleName
                            ?: "image commit failed",
                        )
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ImageGenerationResult.Failed(
                error.message ?: error::class.simpleName
                ?: "image generation failed",
            )
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
