package app.lifeos.next.kernel

import app.lifeos.core.image.ImageAssetDescriptor
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.SemanticArtifactPlan
import app.lifeos.core.model.SemanticArtifactKind
import app.lifeos.core.model.SemanticArtifactClaim
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.StableCognitiveIds
import app.lifeos.core.runtime.artifact.ArtifactContribution
import app.lifeos.core.runtime.artifact.ArtifactCoordinator
import app.lifeos.core.runtime.artifact.ArtifactGenerationCoordinator
import app.lifeos.core.runtime.artifact.ArtifactGenerationRequest
import app.lifeos.core.runtime.artifact.ArtifactId
import app.lifeos.core.runtime.artifact.ArtifactKind
import app.lifeos.core.runtime.artifact.ArtifactPhotonIngress
import app.lifeos.core.runtime.artifact.ArtifactReentryReceipt
import app.lifeos.core.runtime.artifact.CollaborativeArtifactRequest
import app.lifeos.core.runtime.artifact.ImageArtifactProfile
import app.lifeos.core.runtime.artifact.OwnerAssetReviewCandidate
import app.lifeos.core.runtime.artifact.OwnerAssetReviewCoordinator
import app.lifeos.core.runtime.artifact.OwnerAssetReviewSubjectType
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

/**
 * Productive bridge from already-materialized offline images into the collaborative-artifact
 * lifecycle, but with publication held behind the private-owner review boundary.
 *
 * Generated Scene/Image/Artifact/Generation Photons and transformed Image Photons stay outside the
 * canonical Photon store until the exact review candidate is approved by the private owner.
 */
internal class ImageArtifactLifecycleRuntime(
    private val photons: PhotonRepository,
    private val reviews: OwnerAssetReviewCoordinator,
) {
    suspend fun attach(
        context: GoalActionContext,
        result: ImageGenerationResult,
    ): ImageGenerationResult {
        if (result !is ImageGenerationResult.Generated) return result
        val image = result.value
        return try {
            require(!image.scene.processingQueued && !image.image.processingQueued) {
                "Generated image Photons must remain unpublished until owner review"
            }
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
            val sceneContent = buildString {
                append("sceneId=")
                append(image.descriptor.sceneId)
                append(";scenePhotonId=")
                append(image.scene.photon.id.value)
            }
            val renderContent = image.descriptor.encode()
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
                    content = sceneContent,
                    claimIds = setOf("scene"),
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
                    content = renderContent,
                    claimIds = setOf("render"),
                ),
            )
            val semanticPlan = SemanticArtifactPlan(
                kind = SemanticArtifactKind.IMAGE,
                claims = listOf(
                    SemanticArtifactClaim(
                        claimId = "scene",
                        evidence = setOf(
                            PhotonRevisionRef(context.sourcePhoton.id, context.sourcePhoton.revision),
                            PhotonRevisionRef(image.scene.photon.id, image.scene.photon.revision),
                        ),
                        confidenceMicros = (image.scene.photon.confidence * 1_000_000.0).toLong()
                            .coerceIn(0L, 1_000_000L),
                        canonicalContent = sceneContent,
                    ),
                    SemanticArtifactClaim(
                        claimId = "render",
                        evidence = setOf(
                            PhotonRevisionRef(image.scene.photon.id, image.scene.photon.revision),
                            PhotonRevisionRef(image.image.photon.id, image.image.photon.revision),
                        ),
                        confidenceMicros = (image.image.photon.confidence * 1_000_000.0).toLong()
                            .coerceIn(0L, 1_000_000L),
                        canonicalContent = renderContent,
                    ),
                ),
                sourceWorldRevision = 0L,
            )

            val stagedIngress = CapturingArtifactPhotonIngress()
            val stagedGeneration = ArtifactGenerationCoordinator(
                artifacts = ArtifactCoordinator(
                    photons = photons,
                    ingress = stagedIngress,
                ),
                ingress = stagedIngress,
            )
            val artifact = stagedGeneration.finalize(
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
                    semanticPlan = semanticPlan,
                    finalizedAt = finalizedAt,
                    materializedAsset = image.descriptor.asset,
                )
            )
            val revision = requireNotNull(artifact.finalization.artifact.revision) {
                "Generated image artifact must have an immutable revision manifest"
            }
            val stagedPhotons = listOf(image.scene.photon, image.image.photon) + stagedIngress.photons()
            val stagedIds = stagedPhotons.mapTo(linkedSetOf()) { it.id }
            val candidate = OwnerAssetReviewCandidate.create(
                subjectType = OwnerAssetReviewSubjectType.COLLABORATIVE_ARTIFACT,
                subjectId = artifact.finalization.artifact.request.id.value,
                revisionKey = revision.id.value,
                kind = artifact.finalization.artifact.request.kind,
                title = artifact.finalization.artifact.request.title,
                targetMimeType = artifact.finalization.artifact.request.targetMimeType,
                createdAt = artifact.finalization.artifact.finalizedAt,
                participatingModules = revision.participatingModules,
                inputPhotonIds = revision.inputPhotonIds.filterTo(linkedSetOf()) { it !in stagedIds },
                materializedAsset = image.descriptor.asset,
                stagedPhotons = stagedPhotons,
                metadata = mapOf(
                    "width" to image.descriptor.width.toString(),
                    "height" to image.descriptor.height.toString(),
                    "rendererId" to image.rendererId,
                    "sceneId" to image.descriptor.sceneId,
                    "assetSha256" to image.descriptor.asset.sha256,
                ),
            )
            reviews.stage(candidate)

            ImageGenerationResult.Generated(
                image.copy(
                    artifactGeneration = artifact,
                    ownerReviewCandidateId = candidate.id,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ImageGenerationResult.Failed(
                "image-artifact-owner-review-staging-failed:${error::class.simpleName}:${error.message.orEmpty().take(160)}"
            )
        }
    }

    suspend fun attachTransform(
        context: GoalActionContext,
        result: LocalImageTransformExecutionResult,
    ): LocalImageTransformExecutionResult {
        if (result !is LocalImageTransformExecutionResult.Transformed) return result
        return try {
            require(!result.output.processingQueued) {
                "Transformed image Photon must remain unpublished until owner review"
            }
            val descriptor = ImageAssetDescriptor.decode(result.output.photon.content)
            val revisionKey = StableCognitiveIds.fingerprint(
                "owner-reviewed-image-transform/v1",
                result.output.photon.id.value,
                result.output.photon.revision.toString(),
                result.sourcePhotonId.value,
                context.goalPhotonId.value,
                descriptor.asset.sha256,
                *result.operations.map { it.name }.toTypedArray(),
            )
            val candidate = OwnerAssetReviewCandidate.create(
                subjectType = OwnerAssetReviewSubjectType.COLLABORATIVE_ARTIFACT,
                subjectId = "transformed-image:${result.output.photon.id.value}",
                revisionKey = revisionKey,
                kind = ArtifactKind.IMAGE,
                title = "Transformed image ${descriptor.sceneId}",
                targetMimeType = descriptor.asset.mediaType,
                createdAt = result.output.photon.provenance.createdAt,
                participatingModules = setOf("local-image-transform"),
                inputPhotonIds = setOf(result.sourcePhotonId, context.goalPhotonId),
                materializedAsset = descriptor.asset,
                stagedPhotons = listOf(result.output.photon),
                metadata = mapOf(
                    "width" to descriptor.width.toString(),
                    "height" to descriptor.height.toString(),
                    "rendererId" to descriptor.rendererId,
                    "operations" to result.operations.joinToString(",") { it.name },
                    "assetSha256" to descriptor.asset.sha256,
                ),
            )
            reviews.stage(candidate)
            result.copy(ownerReviewCandidateId = candidate.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LocalImageTransformExecutionResult.Failed(
                "image-transform-owner-review-staging-failed:${error::class.simpleName}:${error.message.orEmpty().take(160)}"
            )
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private class CapturingArtifactPhotonIngress : ArtifactPhotonIngress {
        private val staged = linkedMapOf<String, Photon>()

        override suspend fun ingest(photon: Photon): ArtifactReentryReceipt {
            staged[photon.id.value]?.let { existing ->
                require(existing == photon) {
                    "Staged artifact Photon identity ${photon.id.value} was reused with different content"
                }
                return ArtifactReentryReceipt(accepted = false)
            }
            staged[photon.id.value] = photon
            // False is deliberate: this is staging, not canonical DERIVED re-entry.
            return ArtifactReentryReceipt(accepted = false)
        }

        fun photons(): List<Photon> = staged.values.toList()
    }
}

/** Late-bound because production constructs the kernel before CanonicalPhotonIngress. */
internal object ImageArtifactLifecycleRuntimeRegistry {
    @Volatile
    private var runtime: ImageArtifactLifecycleRuntime? = null

    fun install(
        photons: PhotonRepository,
        reviews: OwnerAssetReviewCoordinator,
    ) {
        runtime = ImageArtifactLifecycleRuntime(photons, reviews)
    }

    suspend fun attach(
        context: GoalActionContext,
        result: ImageGenerationResult,
    ): ImageGenerationResult = runtime?.attach(context, result) ?: result

    suspend fun attachTransform(
        context: GoalActionContext,
        result: LocalImageTransformExecutionResult,
    ): LocalImageTransformExecutionResult = runtime?.attachTransform(context, result) ?: result
}
