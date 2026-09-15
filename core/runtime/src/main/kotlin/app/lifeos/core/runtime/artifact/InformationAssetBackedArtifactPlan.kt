package app.lifeos.core.runtime.artifact

import app.lifeos.core.runtime.informationasset.InformationAssetRevisionRef

/**
 * Immutable plan for one artifact run whose semantic inputs have already crossed the guarded
 * InformationAsset bridge. The plan keeps exact semantic revision refs visible instead of reducing
 * them to untyped contribution text.
 */
data class InformationAssetBackedArtifactPlan(
    val request: CollaborativeArtifactRequest,
    val inputs: List<ValidatedInformationAssetArtifactBatch>,
) {
    init {
        require(inputs.isNotEmpty()) { "InformationAsset-backed artifact requires semantic inputs" }
        val logicalAssetIds = inputs.map { it.batch.sourceRevision.assetId }
        require(logicalAssetIds.distinct().size == logicalAssetIds.size) {
            "Artifact plan cannot bind multiple revisions of the same InformationAsset"
        }
        val availableFields = inputs
            .flatMap { it.batch.contributions }
            .mapTo(mutableSetOf()) { it.field }
        val missingRequiredFields = request.requiredFields - availableFields
        require(missingRequiredFields.isEmpty()) {
            "InformationAsset-backed artifact is missing required fields: ${missingRequiredFields.sorted().joinToString(",")}" 
        }
    }

    val revisionRefs: List<InformationAssetRevisionRef> = inputs
        .map { it.batch.sourceRevision }
        .sortedWith(
            compareBy<InformationAssetRevisionRef> { it.assetId.value }
                .thenBy { it.revisionId.value }
                .thenBy { it.photonId.value }
        )

    val contributions: List<ArtifactContribution> = inputs
        .flatMap { it.batch.contributions }
        .sortedWith(
            compareBy<ArtifactContribution>({ it.field }, { it.module }, { it.source }, { it.id })
        )
}
