package app.lifeos.core.runtime.artifact

import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.SemanticArtifactKind
import app.lifeos.core.model.SemanticArtifactPlan
import app.lifeos.core.runtime.informationasset.InformationAssetRevisionRef

/**
 * Immutable fail-closed plan for one artifact run whose semantic inputs have crossed the
 * validated InformationAsset boundary. Exact semantic revisions and the closed semantic render
 * plan remain visible together; neither is reduced to untyped contribution text.
 */
data class InformationAssetBackedArtifactPlan(
    val request: CollaborativeArtifactRequest,
    val inputs: List<ValidatedInformationAssetArtifactBatch>,
    val semanticPlan: SemanticArtifactPlan,
) {
    init {
        require(inputs.isNotEmpty()) { "InformationAsset-backed artifact requires semantic inputs" }
        val logicalAssetIds = inputs.map { it.batch.sourceRevision.assetId }
        require(logicalAssetIds.distinct().size == logicalAssetIds.size) {
            "Artifact plan cannot bind multiple revisions of the same InformationAsset"
        }
        require(semanticPlan.unresolvedClaimIds.isEmpty()) {
            "InformationAsset-backed artifact cannot materialize unresolved semantic claims"
        }
        require(semanticPlan.kind == expectedSemanticKind(request)) {
            "Semantic artifact plan kind ${semanticPlan.kind} is incompatible with ${request.kind}"
        }

        val allContributions = inputs.flatMap { it.batch.contributions }
        val availableFields = allContributions.mapTo(mutableSetOf()) { it.field }
        val missingRequiredFields = request.requiredFields - availableFields
        require(missingRequiredFields.isEmpty()) {
            "InformationAsset-backed artifact is missing required fields: " +
                missingRequiredFields.sorted().joinToString(",")
        }

        val renderedClaimIds = allContributions.flatMap { contribution ->
            require(contribution.claimIds.size == 1) {
                "InformationAsset contribution must map to exactly one semantic claim"
            }
            contribution.claimIds
        }.toSet()
        val plannedClaimIds = semanticPlan.claims.mapTo(mutableSetOf()) { it.claimId }
        require(renderedClaimIds == plannedClaimIds) {
            "Semantic plan and InformationAsset contributions must contain the same claim set"
        }

        inputs.forEach { input ->
            val exactRevisionEvidence = PhotonRevisionRef(
                photonId = input.batch.source.photonId,
                revision = input.batch.source.photonRevision,
            )
            input.batch.contributions.forEach { contribution ->
                val claimId = contribution.claimIds.single()
                val claim = requireNotNull(semanticPlan.claim(claimId)) {
                    "InformationAsset contribution references missing semantic claim: $claimId"
                }
                require(exactRevisionEvidence in claim.evidence) {
                    "Semantic claim $claimId is not bound to exact InformationAsset revision Photon"
                }
                require(claim.canonicalContent == contribution.content) {
                    "Semantic plan changed InformationAsset claim content: $claimId"
                }
            }
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

    private fun expectedSemanticKind(request: CollaborativeArtifactRequest): SemanticArtifactKind =
        when (request.kind) {
            ArtifactKind.IMAGE -> SemanticArtifactKind.IMAGE
            ArtifactKind.CODE -> SemanticArtifactKind.TASK
            ArtifactKind.DOCUMENT,
            ArtifactKind.REPORT -> if (request.targetMimeType == "application/pdf") {
                SemanticArtifactKind.PDF
            } else {
                SemanticArtifactKind.TEXT
            }
            ArtifactKind.OTHER -> SemanticArtifactKind.TEXT
        }
}
