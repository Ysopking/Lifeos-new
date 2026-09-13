package app.lifeos.next.kernel

import app.lifeos.core.image.ImageAssetDescriptor
import app.lifeos.core.runtime.artifact.ArtifactGenerationResult

data class GeneratedImageResult(
    val scene: PhotonSubmissionResult,
    val image: PhotonSubmissionResult,
    val descriptor: ImageAssetDescriptor,
    val rendererId: String,
    val artifactGeneration: ArtifactGenerationResult? = null,
)

sealed interface ImageGenerationResult {
    data class Generated(val value: GeneratedImageResult) : ImageGenerationResult
    data class Blocked(val reasons: List<String>) : ImageGenerationResult {
        init { require(reasons.isNotEmpty()) }
    }
    data class Failed(val message: String) : ImageGenerationResult {
        init { require(message.isNotBlank()) }
    }
}
