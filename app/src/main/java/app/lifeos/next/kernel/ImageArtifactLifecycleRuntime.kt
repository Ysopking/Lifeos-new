package app.lifeos.next.kernel

import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.artifact.ArtifactContribution
import app.lifeos.core.runtime.artifact.ArtifactGenerationCoordinator
import app.lifeos.core.runtime.artifact.ArtifactGenerationRequest
import app.lifeos.core.runtime.artifact.ArtifactId
import app.lifeos.core.runtime.artifact.ArtifactKind
import app.lifeos.core.runtime.artifact.CollaborativeArtifactRequest
import app.lifeos.core.runtime.artifact.ImageArtifactProfile
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

/**
 * Productive bridge from the already-materialized offline image result into the immutable
 * collaborative-artifact lifecycle. The binary body remains in BinaryAssetStore; artifact Photons
 * only retain AssetRef plus deterministic generation and lineage metadata.
 */
internal class ImageArtifactLifecycleRuntime(
    private val generation: ArtifactGenerationCoordinator,
) {
    suspend fun attach(
        context: GoalActionContext,
        result: ImageGenerationResult,
    ): ImageGenerationResult {
        if (result !is ImageGenerationResult.Generated) return result
        val image = result.value
        return try {
            val finalizedAt = image.image.photon.provenance.createdAt
            val sourceParents = setOf(
                context.sourcePhoton.id,
                context.goalPhotonId,
                image.scene.photon.id,
            )
            val renderParents = setOf(
                context.sourcePhoton.id,
                context.goalPhotonId,
                image.scene.photon.id,
                image.image.photon.id,
            )
            val contributions = listOf(
                ArtifactContribution.create(
                    module = "scene-compiler",
                    field = "scene",
                    source = "offline-procedural-image",
                    provenance = Provenance(
                        source = "lifeos.image.scene",
                        actor = "lifeos.scene",
                        createdAt = finalizedAt,
                        parentIds = sourceParents,
                    ),
                    confidence = image.scene.photon.confidence,
                    content = buildString {
                        append("sceneId=")
                        append(image.descriptor.sceneId)
                        append(";scenePhotonId=")
                        append(image.scene.photon.id.value)
                    },
                ),
                ArtifactContribution.create(
                    module = "image-renderer",
                    field = "render",
                    source = "offline-procedural-image",
                    provenance = Provenance(
                        source = "lifeos.image.render",
                        actor = image.rendererId,
                        createdAt = finalizedAt,
                        parentIds = renderParents,
                    ),
                    confidence = image.image.photon.confidence,
                    content = image.descriptor.encode(),
                ),
            )
            val artifact = generation.finalize(
                ArtifactGenerationRequest(
                    request = CollaborativeArtifactRequest(
                        id = ArtifactId("generated-image:${image.image.photon.id.value}"),
                        kind = ArtifactKind.IMAGE,
                        title = "Generated image ${image.descriptor.sceneId}",
                        targetMimeType = image.descriptor.asset.mediaType,
                        requestedAt = context.sourcePhoton.provenance.createdAt,
                        requiredFields = setOf("scene", "render"),
                    ),
                    profile = ImageArtifactProfile(
                        width = image.descriptor.width,
                        height = image.descriptor.height,
                        promptFingerprint = sha256(context.sourcePhoton.content),
                        model = image.rendererId,
                    ),
                    contributions = contributions,
                    finalizedAt = finalizedAt,
                    materializedAsset = image.descriptor.asset,
                )
            )
            ImageGenerationResult.Generated(
                image.copy(artifactGeneration = artifact),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ImageGenerationResult.Failed(
                "image-artifact-finalization-failed:${error::class.simpleName}:${error.message.orEmpty().take(160)}"
            )
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

/** Late-bound because production constructs the kernel before CanonicalPhotonIngress. */
internal object ImageArtifactLifecycleRuntimeRegistry {
    @Volatile
    private var runtime: ImageArtifactLifecycleRuntime? = null

    fun install(generation: ArtifactGenerationCoordinator) {
        runtime = ImageArtifactLifecycleRuntime(generation)
    }

    suspend fun attach(
        context: GoalActionContext,
        result: ImageGenerationResult,
    ): ImageGenerationResult = runtime?.attach(context, result) ?: result
}
