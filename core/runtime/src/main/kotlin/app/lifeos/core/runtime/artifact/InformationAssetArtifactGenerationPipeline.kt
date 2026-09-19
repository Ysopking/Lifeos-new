package app.lifeos.core.runtime.artifact

import app.lifeos.core.model.AssetRef
import app.lifeos.core.model.SemanticArtifactPlan
import app.lifeos.core.runtime.informationasset.InformationAssetRevisionVerifier
import app.lifeos.core.runtime.informationasset.InformationAssetValidator
import app.lifeos.core.runtime.informationasset.StandardInformationAssetValidation
import java.time.Instant

data class InformationAssetArtifactGenerationRequest(
    val request: CollaborativeArtifactRequest,
    val semanticInputs: List<InformationAssetArtifactInput>,
    val semanticPlan: SemanticArtifactPlan,
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
        require(generation.finalization.artifact.revision?.semanticPlanFingerprint == plan.semanticPlan.fingerprint) {
            "Generation result dropped semantic plan identity"
        }
    }
}

/**
 * Canonical fail-closed path from semantic InformationAsset revisions to a materialized Artifact.
 * It composes semantic validation, exact source verification, closed semantic-plan binding and the
 * existing ArtifactGenerationCoordinator. No parallel persistence or activation authority is added.
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
            semanticPlan = input.semanticPlan,
        )
        val generation = generationCoordinator.finalize(
            ArtifactGenerationRequest(
                request = input.request,
                profile = input.profile,
                contributions = plan.contributions,
                semanticPlan = plan.semanticPlan,
                semanticInputRevisions = plan.revisionRefs,
                finalizedAt = input.finalizedAt,
                materializedAsset = input.materializedAsset,
                parentRevision = input.parentRevision,
            )
        )
        return InformationAssetArtifactGenerationResult(
            validatedInputs = validatedInputs,
            plan = plan,
            generation = generation,
        )
    }
}
