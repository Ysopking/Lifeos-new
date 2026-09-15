package app.lifeos.core.runtime.artifact

import app.lifeos.core.model.AssetRef
import app.lifeos.core.runtime.informationasset.InformationAssetRevisionVerifier
import app.lifeos.core.runtime.informationasset.InformationAssetValidator
import app.lifeos.core.runtime.informationasset.StandardInformationAssetValidation
import java.time.Instant

data class InformationAssetArtifactGenerationRequest(
    val request: CollaborativeArtifactRequest,
    val semanticInputs: List<InformationAssetArtifactInput>,
    val profile: ArtifactGenerationProfile,
    val evaluatedAt: Instant,
    val finalizedAt: Instant,
    val materializedAsset: AssetRef,
    val parentRevision: ArtifactRevisionRef? = null,
) {
    init {
        require(semanticInputs.isNotEmpty()) {
            "InformationAsset-backed generation requires semantic inputs"
        }
        require(finalizedAt >= evaluatedAt) {
            "Artifact finalization cannot predate semantic validation"
        }
    }
}

data class InformationAssetArtifactGenerationResult(
    val validatedInputs: List<ValidatedInformationAssetArtifactBatch>,
    val plan: InformationAssetBackedArtifactPlan,
    val generation: ArtifactGenerationResult,
) {
    init {
        val plannedRefs = plan.revisionRefs.toSet()
        val validatedRefs = validatedInputs.map { it.batch.sourceRevision }.toSet()
        require(plannedRefs == validatedRefs) {
            "Generation result plan does not match validated InformationAsset revisions"
        }
        val artifactRevision = requireNotNull(generation.finalization.artifact.revision)
        require(artifactRevision.inputPhotonIds.containsAll(plannedRefs.map { it.photonId })) {
            "Generation result Artifact revision dropped semantic input Photons"
        }
        require(generation.generationPhoton.provenance.parentIds.containsAll(plannedRefs.map { it.photonId })) {
            "Generation provenance dropped semantic input Photons"
        }
    }
}

/**
 * Canonical fail-closed path from semantic InformationAsset revisions to a materialized Artifact.
 * It composes the existing semantic validator/verifier, guarded bridge, Artifact plan and generation
 * coordinator without creating a parallel persistence, validation or revision authority.
 */
class InformationAssetArtifactGenerationPipeline(
    verifier: InformationAssetRevisionVerifier,
    private val generationCoordinator: ArtifactGenerationCoordinator,
    validator: InformationAssetValidator = StandardInformationAssetValidation.validator(),
) {
    private val guardedBridge = ValidatedInformationAssetArtifactBridge(
        verifier = verifier,
        validator = validator,
    )

    suspend fun finalize(
        input: InformationAssetArtifactGenerationRequest,
    ): InformationAssetArtifactGenerationResult {
        val validatedInputs = input.semanticInputs.map { semanticInput ->
            guardedBridge.create(
                input = semanticInput,
                evaluatedAt = input.evaluatedAt,
            )
        }
        val plan = InformationAssetBackedArtifactPlan(
            request = input.request,
            inputs = validatedInputs,
        )
        val generation = generationCoordinator.finalize(
            ArtifactGenerationRequest(
                request = input.request,
                profile = input.profile,
                contributions = plan.contributions,
                finalizedAt = input.finalizedAt,
                materializedAsset = input.materializedAsset,
                parentRevision = input.parentRevision,
                semanticInputRevisions = plan.revisionRefs,
            )
        )
        return InformationAssetArtifactGenerationResult(
            validatedInputs = validatedInputs,
            plan = plan,
            generation = generation,
        )
    }
}
