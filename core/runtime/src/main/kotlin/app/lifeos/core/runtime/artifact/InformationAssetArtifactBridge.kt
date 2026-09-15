package app.lifeos.core.runtime.artifact

import app.lifeos.core.model.Photon
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.informationasset.InformationAssetPhotonContract
import app.lifeos.core.runtime.informationasset.InformationAssetRevision
import app.lifeos.core.runtime.informationasset.InformationAssetRevisionRef
import app.lifeos.core.runtime.informationasset.InformationClaimState

object InformationAssetArtifactBridgeContract {
    const val PROVENANCE_SOURCE = "lifeos.information-asset-artifact-bridge"
    const val PROVENANCE_ACTOR = "lifeos-runtime"
}

data class InformationAssetArtifactInput(
    val revision: InformationAssetRevision,
    val revisionPhoton: Photon,
) {
    init {
        require(revisionPhoton.mimeType == InformationAssetPhotonContract.MIME_TYPE) {
            "InformationAsset artifact input requires an InformationAsset Photon"
        }
        require("information-asset-id:${revision.request.id.value}" in revisionPhoton.tags) {
            "InformationAsset Photon belongs to a different logical asset"
        }
        require("information-asset-revision:${revision.manifest.id.value}" in revisionPhoton.tags) {
            "InformationAsset Photon does not represent the supplied revision"
        }
        require("information-asset-state-hash:${revision.manifest.stateHash.value}" in revisionPhoton.tags) {
            "InformationAsset Photon state hash does not match the supplied revision"
        }
    }

    fun revisionRef(): InformationAssetRevisionRef = InformationAssetRevisionRef(
        assetId = revision.request.id,
        revisionId = revision.manifest.id,
        photonId = revisionPhoton.id,
    )
}

data class InformationAssetArtifactContributionBatch(
    val sourceRevision: InformationAssetRevisionRef,
    val contributions: List<ArtifactContribution>,
) {
    init {
        require(contributions.isNotEmpty()) { "InformationAsset bridge requires at least one contribution" }
        require(contributions.all { sourceRevision.photonId in it.provenance.parentIds }) {
            "Every bridged contribution must retain the exact InformationAsset revision Photon"
        }
    }
}

/**
 * Converts semantic claims into collaborative Artifact contributions while preserving the exact
 * InformationAsset revision Photon as provenance. Rejected/unresolved claims are deliberately not
 * materialized here. Validation and exact-source re-verification are added by the guarded bridge.
 */
class InformationAssetArtifactContributionFactory {
    fun create(input: InformationAssetArtifactInput): InformationAssetArtifactContributionBatch {
        val revision = input.revision
        val revisionRef = input.revisionRef()
        val source = "information-asset:${revision.request.id.value}:${revision.manifest.id.value}"
        val module = "information-asset:${revision.request.kind.name.lowercase()}"
        val createdAt = input.revisionPhoton.provenance.createdAt
        val contributions = revision.claims
            .asSequence()
            .filter { it.state == InformationClaimState.SUPPORTED || it.state == InformationClaimState.ASSUMPTION }
            .sortedWith(compareBy({ it.semanticKey }, { it.id.value }))
            .map { claim ->
                ArtifactContribution.create(
                    module = module,
                    field = claim.semanticKey,
                    source = source,
                    provenance = Provenance(
                        source = InformationAssetArtifactBridgeContract.PROVENANCE_SOURCE,
                        actor = InformationAssetArtifactBridgeContract.PROVENANCE_ACTOR,
                        createdAt = createdAt,
                        parentIds = setOf(input.revisionPhoton.id),
                    ),
                    confidence = claim.confidence,
                    content = claim.statement,
                    contributedAt = createdAt,
                )
            }
            .toList()
        return InformationAssetArtifactContributionBatch(
            sourceRevision = revisionRef,
            contributions = contributions,
        )
    }
}
