package app.lifeos.core.runtime.artifact

import app.lifeos.core.model.AssetRef
import app.lifeos.core.runtime.informationasset.InformationAssetRevisionRef
import java.time.Instant

data class InformationAssetBackedArtifactFinalization(
    val semanticInputs: List<InformationAssetRevisionRef>,
    val finalization: ArtifactFinalizationResult,
) {
    init {
        val revision = requireNotNull(finalization.artifact.revision) {
            "InformationAsset-backed artifacts require an immutable Artifact revision manifest"
        }
        val missing = semanticInputs.map { it.photonId }.toSet() - revision.inputPhotonIds
        require(missing.isEmpty()) {
            "Finalized Artifact dropped InformationAsset revision Photon inputs: " +
                missing.map { it.value }.sorted().joinToString(",")
        }
    }
}

/**
 * Productive semantic finalization adapter. ArtifactCoordinator remains the only Artifact
 * validation/revision/persistence authority; this adapter only carries the already-validated
 * InformationAsset revisions and closed semantic plan into that authority.
 */
class InformationAssetBackedArtifactCoordinator(
    private val artifacts: ArtifactCoordinator,
) {
    suspend fun finalize(
        plan: InformationAssetBackedArtifactPlan,
        finalizedAt: Instant,
        parentRevision: ArtifactRevisionRef? = null,
        materializedAsset: AssetRef? = null,
    ): InformationAssetBackedArtifactFinalization {
        val finalization = artifacts.finalize(
            request = plan.request,
            contributions = plan.contributions,
            finalizedAt = finalizedAt,
            parentRevision = parentRevision,
            materializedAsset = materializedAsset,
            semanticPlan = plan.semanticPlan,
        )
        return InformationAssetBackedArtifactFinalization(
            semanticInputs = plan.revisionRefs,
            finalization = finalization,
        )
    }
}
