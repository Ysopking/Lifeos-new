package app.lifeos.core.runtime.genesis

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType

/** Durable, non-activating evidence for one Genesis proposal/handoff decision. */
object GenesisProposalPhoton {
    const val MIME = "application/vnd.lifeos.genesis-proposal+text"
    private const val ID_PREFIX = "genesis-proposal_"

    fun create(
        proposal: GenesisProposal,
        sourcePhoton: Photon,
        goalPhotonId: PhotonId,
    ): Photon {
        val id = PhotonId(
            ID_PREFIX + StableFieldIds.fingerprint(
                "genesis-proposal-photon/v1",
                proposal.id,
                sourcePhoton.id.value,
                sourcePhoton.revision.toString(),
                goalPhotonId.value,
            )
        )
        return Photon(
            id = id,
            revision = 1L,
            content = buildString {
                appendLine("LIFEOS_GENESIS_PROPOSAL_V1")
                append("proposalId=").appendLine(proposal.id)
                append("capability=").appendLine(proposal.gap.requirement.capabilityId.value)
                append("gapType=").appendLine(proposal.gap.type.name)
                append("solution=").appendLine(proposal.selected.kind.name)
                append("target=").appendLine(proposal.handoff.target.name)
                append("referenceId=").appendLine(proposal.handoff.referenceId)
                append("requiresExplicitApproval=").appendLine(proposal.handoff.requiresExplicitApproval)
                append("activationAllowed=false")
            },
            mimeType = MIME,
            phase = PhotonPhase.ACTIVE,
            semanticMass = 0.75,
            energy = 0.45,
            confidence = 1.0,
            provenance = Provenance(
                source = "genesis",
                actor = "lifeos",
                createdAt = sourcePhoton.provenance.createdAt,
                parentIds = setOf(sourcePhoton.id, goalPhotonId),
            ),
            relations = setOf(
                PhotonRelation(sourcePhoton.id, RelationType.DERIVED_FROM, 1.0),
                PhotonRelation(goalPhotonId, RelationType.REFERENCES, 1.0),
            ),
            tags = buildSet {
                add("genesis-proposal")
                add("genesis-handoff")
                add("genesis-target:${proposal.handoff.target.name.lowercase()}")
                add("non-activating")
                sourcePhoton.tags.filterTo(this) {
                    it.startsWith("conversation:") || it.startsWith("turn:")
                }
            },
        )
    }
}
