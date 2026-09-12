package app.lifeos.core.runtime.capability

import app.lifeos.core.model.CanonicalPhotonState
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import app.lifeos.core.model.StableCognitiveIds

/** Durable, non-activating evidence for one unresolved capability gap. */
object CapabilityGapPhoton {
    const val MIME_TYPE = "application/vnd.lifeos.capability-gap+text"

    fun create(
        gap: CapabilityGap,
        sourcePhoton: Photon,
        goalPhotonId: PhotonId,
        goalPhotonRevision: Long,
    ): Photon {
        require(goalPhotonRevision > 0L)
        val sourceState = CanonicalPhotonState.inputHash(sourcePhoton).value
        val requirement = gap.requirement
        val fingerprint = StableCognitiveIds.fingerprint(
            "capability-gap-photon/v1",
            sourcePhoton.id.value,
            sourcePhoton.revision.toString(),
            sourceState,
            goalPhotonId.value,
            goalPhotonRevision.toString(),
            requirement.capabilityId.value,
            requirement.severity.name,
            gap.type.name,
            *requirement.requiredInputs.sorted().map { "input:$it" }.toTypedArray(),
            *requirement.requiredOutputs.sorted().map { "output:$it" }.toTypedArray(),
            *gap.candidateProviderIds.distinct().sorted().map { "candidate:$it" }.toTypedArray(),
        )
        return Photon(
            id = PhotonId("capability-gap-${fingerprint.take(48)}"),
            content = buildString {
                appendLine("schema=1")
                appendLine("capability_id=${requirement.capabilityId.value}")
                appendLine("severity=${requirement.severity.name}")
                appendLine("gap_type=${gap.type.name}")
                appendLine("source_photon_id=${sourcePhoton.id.value}")
                appendLine("source_revision=${sourcePhoton.revision}")
                appendLine("source_state_hash=$sourceState")
                appendLine("goal_photon_id=${goalPhotonId.value}")
                appendLine("goal_revision=$goalPhotonRevision")
                appendLine("required_inputs=${requirement.requiredInputs.sorted().joinToString(",")}")
                appendLine("required_outputs=${requirement.requiredOutputs.sorted().joinToString(",")}")
                append("candidate_providers=${gap.candidateProviderIds.distinct().sorted().joinToString(",")}")
            },
            mimeType = MIME_TYPE,
            semanticMass = 0.9,
            energy = 0.7,
            confidence = 1.0,
            provenance = Provenance(
                source = "capability-gap",
                actor = "lifeos-router",
                createdAt = sourcePhoton.provenance.createdAt,
                parentIds = setOf(sourcePhoton.id, goalPhotonId),
            ),
            relations = setOf(
                PhotonRelation(sourcePhoton.id, RelationType.DERIVED_FROM),
                PhotonRelation(goalPhotonId, RelationType.REFERENCES),
            ),
            tags = setOf(
                "capability-gap",
                "capability:${requirement.capabilityId.value}",
                "gap-type:${gap.type.name.lowercase()}",
                "source-state:$sourceState",
                "activation-allowed:false",
                "genesis-candidate",
            ),
        )
    }
}
