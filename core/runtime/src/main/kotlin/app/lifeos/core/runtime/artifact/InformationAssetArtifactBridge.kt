package app.lifeos.core.runtime.artifact

import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.model.CognitiveStateHash
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.informationasset.InformationAssetId
import app.lifeos.core.runtime.informationasset.InformationAssetPhotonContract
import app.lifeos.core.runtime.informationasset.InformationAssetResolutionState
import app.lifeos.core.runtime.informationasset.InformationAssetRevision
import app.lifeos.core.runtime.informationasset.InformationAssetRevisionId
import app.lifeos.core.runtime.informationasset.InformationAssetRevisionRef
import app.lifeos.core.runtime.informationasset.InformationClaimState

object InformationAssetArtifactBridgeContract {
    const val PROVENANCE_SOURCE = "lifeos.information-asset-artifact-bridge"
    const val PROVENANCE_ACTOR = "lifeos-runtime"
    const val SOURCE_SCHEMA = "information-asset-artifact-source/v1"
}

data class InformationAssetArtifactSourceBinding(
    val assetId: InformationAssetId,
    val revisionId: InformationAssetRevisionId,
    val stateHash: CognitiveStateHash,
    val photonId: PhotonId,
    val photonRevision: Long,
    val domainIds: Set<FieldDomainId>,
    val resolution: InformationAssetResolutionState,
) {
    init {
        require(photonRevision > 0) { "InformationAsset source Photon revision must be positive" }
        require(domainIds.isNotEmpty()) { "InformationAsset artifact source requires at least one domain" }
    }

    val sourceRevision: InformationAssetRevisionRef
        get() = InformationAssetRevisionRef(
            assetId = assetId,
            revisionId = revisionId,
            photonId = photonId,
        )

    fun sourceDescriptor(): String = buildString {
        append(InformationAssetArtifactBridgeContract.SOURCE_SCHEMA)
        append(":asset="); append(assetId.value)
        append(":revision="); append(revisionId.value)
        append(":state="); append(stateHash.value)
        append(":photon="); append(photonId.value)
        append(":photon-revision="); append(photonRevision)
        append(":resolution="); append(resolution.name)
        append(":domains=")
        append(domainIds.map { it.value }.sorted().joinToString(","))
    }
}

data class InformationAssetArtifactInput(
    val revision: InformationAssetRevision,
    val revisionPhoton: Photon,
) {
    init {
        require(revisionPhoton.mimeType == InformationAssetPhotonContract.MIME_TYPE) {
            "InformationAsset artifact input requires an InformationAsset Photon"
        }
        require(revisionPhoton.provenance.source == InformationAssetPhotonContract.PROVENANCE_SOURCE) {
            "InformationAsset artifact input has unexpected provenance source"
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
        require("information-asset-resolution:${revision.manifest.resolution.name.lowercase()}" in revisionPhoton.tags) {
            "InformationAsset Photon resolution does not match the supplied revision"
        }
        revision.manifest.domainIds.forEach { domain ->
            require("information-domain:${domain.value}" in revisionPhoton.tags) {
                "InformationAsset Photon dropped domain ${domain.value}"
            }
        }
    }

    fun sourceBinding(): InformationAssetArtifactSourceBinding = InformationAssetArtifactSourceBinding(
        assetId = revision.request.id,
        revisionId = revision.manifest.id,
        stateHash = revision.manifest.stateHash,
        photonId = revisionPhoton.id,
        photonRevision = revisionPhoton.revision,
        domainIds = revision.manifest.domainIds,
        resolution = revision.manifest.resolution,
    )
}

data class InformationAssetArtifactContributionBatch(
    val source: InformationAssetArtifactSourceBinding,
    val contributions: List<ArtifactContribution>,
) {
    val sourceRevision: InformationAssetRevisionRef
        get() = source.sourceRevision

    init {
        require(contributions.isNotEmpty()) {
            "InformationAsset bridge requires at least one non-rejected claim"
        }
        require(contributions.all { source.photonId in it.provenance.parentIds }) {
            "Every bridged contribution must retain the exact InformationAsset revision Photon"
        }
        val expectedSource = source.sourceDescriptor()
        require(contributions.all { it.source == expectedSource }) {
            "Every bridged contribution must retain the exact InformationAsset source descriptor"
        }
    }
}

/**
 * Projects semantic InformationAsset claims into the existing collaborative-artifact contribution
 * model. It does not create a second Artifact authority: ArtifactCoordinator still owns validation,
 * revision identity, persistence and canonical Photon re-entry.
 *
 * Supported, explicit-assumption and unresolved claims remain visible. Rejected claims are audit
 * history and are deliberately not materialized into owner-facing artifact content.
 */
class InformationAssetArtifactContributionFactory {
    fun create(input: InformationAssetArtifactInput): InformationAssetArtifactContributionBatch {
        val revision = input.revision
        val source = input.sourceBinding()
        val sourceDescriptor = source.sourceDescriptor()
        val module = "information-asset:${revision.request.kind.name.lowercase()}"
        val createdAt = input.revisionPhoton.provenance.createdAt

        val contributions = revision.claims
            .asSequence()
            .filter { it.state != InformationClaimState.REJECTED }
            .sortedWith(compareBy({ it.semanticKey }, { it.id.value }))
            .map { claim ->
                ArtifactContribution.create(
                    module = module,
                    field = claim.semanticKey,
                    source = sourceDescriptor,
                    provenance = Provenance(
                        source = InformationAssetArtifactBridgeContract.PROVENANCE_SOURCE,
                        actor = InformationAssetArtifactBridgeContract.PROVENANCE_ACTOR,
                        createdAt = createdAt,
                        parentIds = setOf(input.revisionPhoton.id),
                    ),
                    confidence = claim.confidence,
                    content = claim.statement,
                    contributedAt = createdAt,
                    claimIds = setOf(claim.id.value),
                )
            }
            .toList()

        return InformationAssetArtifactContributionBatch(
            source = source,
            contributions = contributions,
        )
    }
}
