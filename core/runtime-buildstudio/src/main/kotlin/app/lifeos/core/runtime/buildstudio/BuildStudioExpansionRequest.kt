package app.lifeos.core.runtime.buildstudio

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import app.lifeos.core.model.StableCognitiveIds
import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.genesis.GenesisHandoff
import app.lifeos.core.runtime.genesis.GenesisHandoffTarget

/**
 * Credential-free request crossing from Android/runtime to an authorized BuildStudio host.
 * The host, not the APK, binds repository/source-commit/workspace authority.
 */
data class BuildStudioExpansionRequest(
    val gap: CapabilityGap,
    val genesisHandoff: GenesisHandoff,
    val sourcePhotonId: PhotonId,
    val sourcePhotonRevision: Long,
    val goalPhotonId: PhotonId,
    val goalPhotonRevision: Long,
    val gapPhotonId: PhotonId,
) {
    init {
        require(genesisHandoff.target == GenesisHandoffTarget.BUILD_STUDIO)
        require(!genesisHandoff.activationAllowed)
        require(sourcePhotonRevision > 0L && goalPhotonRevision > 0L)
    }

    val id: String = StableCognitiveIds.fingerprint(
        "buildstudio-expansion-request/v1",
        genesisHandoff.payloadFingerprint,
        sourcePhotonId.value,
        sourcePhotonRevision.toString(),
        goalPhotonId.value,
        goalPhotonRevision.toString(),
        gapPhotonId.value,
        gap.requirement.capabilityId.value,
        gap.requirement.severity.name,
        gap.type.name,
        *gap.requirement.requiredInputs.sorted().map { "input:$it" }.toTypedArray(),
        *gap.requirement.requiredOutputs.sorted().map { "output:$it" }.toTypedArray(),
        *gap.candidateProviderIds.distinct().sorted().map { "candidate:$it" }.toTypedArray(),
    )

    val activationAllowed: Boolean = false

    fun toPhoton(sourcePhoton: Photon): Photon {
        require(sourcePhoton.id == sourcePhotonId && sourcePhoton.revision == sourcePhotonRevision)
        return Photon(
            id = PhotonId("buildstudio-request-${id.take(48)}"),
            content = buildString {
                appendLine("schema=1")
                appendLine("request_id=$id")
                appendLine("genesis_proposal_id=${genesisHandoff.proposalId}")
                appendLine("handoff_fingerprint=${genesisHandoff.payloadFingerprint}")
                appendLine("capability_id=${gap.requirement.capabilityId.value}")
                appendLine("source_photon_id=${sourcePhotonId.value}")
                appendLine("source_revision=$sourcePhotonRevision")
                appendLine("goal_photon_id=${goalPhotonId.value}")
                appendLine("goal_revision=$goalPhotonRevision")
                appendLine("gap_photon_id=${gapPhotonId.value}")
                append("activation_allowed=false")
            },
            mimeType = "application/vnd.lifeos.buildstudio-expansion-request+text",
            semanticMass = 0.8,
            energy = 0.4,
            confidence = 1.0,
            provenance = Provenance(
                source = "genesis-buildstudio-handoff",
                actor = "lifeos",
                createdAt = sourcePhoton.provenance.createdAt,
                parentIds = setOf(sourcePhotonId, goalPhotonId, gapPhotonId),
            ),
            relations = setOf(
                PhotonRelation(sourcePhotonId, RelationType.DERIVED_FROM),
                PhotonRelation(goalPhotonId, RelationType.REFERENCES),
                PhotonRelation(gapPhotonId, RelationType.DERIVED_FROM),
            ),
            tags = setOf(
                "buildstudio-expansion-request",
                "capability:${gap.requirement.capabilityId.value}",
                "activation-allowed:false",
                "authorized-host-required",
            ),
        )
    }
}

object BuildStudioExpansionOutcomePhoton {
    fun create(
        request: BuildStudioExpansionRequest,
        requestPhotonId: PhotonId,
        result: BuildStudioResult,
        sourcePhoton: Photon,
    ): Photon {
        val resultFingerprint = when (result) {
            is BuildStudioResult.CandidateReady -> result.verification.id
            is BuildStudioResult.Rejected -> StableCognitiveIds.fingerprint(
                "buildstudio-expansion-rejected/v1",
                result.stage,
                result.verification?.id.orEmpty(),
                *result.failures.sorted().toTypedArray(),
            )
            is BuildStudioResult.Failed -> StableCognitiveIds.fingerprint(
                "buildstudio-expansion-failed/v1",
                result.stage,
                result.reason,
            )
        }
        val status = when (result) {
            is BuildStudioResult.CandidateReady -> "CANDIDATE_READY"
            is BuildStudioResult.Rejected -> "REJECTED"
            is BuildStudioResult.Failed -> "FAILED"
        }
        val fingerprint = StableCognitiveIds.fingerprint(
            "buildstudio-expansion-outcome-photon/v1",
            request.id,
            requestPhotonId.value,
            status,
            resultFingerprint,
        )
        return Photon(
            id = PhotonId("buildstudio-outcome-${fingerprint.take(48)}"),
            content = buildString {
                appendLine("schema=1")
                appendLine("request_id=${request.id}")
                appendLine("status=$status")
                appendLine("result_fingerprint=$resultFingerprint")
                append("activation_allowed=false")
            },
            mimeType = "application/vnd.lifeos.buildstudio-expansion-outcome+text",
            semanticMass = 0.75,
            energy = 0.3,
            confidence = 1.0,
            provenance = Provenance(
                source = "buildstudio-host-evidence",
                actor = "lifeos",
                createdAt = sourcePhoton.provenance.createdAt,
                parentIds = setOf(requestPhotonId, request.gapPhotonId),
            ),
            relations = setOf(
                PhotonRelation(requestPhotonId, RelationType.DERIVED_FROM),
                PhotonRelation(request.gapPhotonId, RelationType.REFERENCES),
            ),
            tags = setOf(
                "buildstudio-expansion-outcome",
                "buildstudio-status:${status.lowercase()}",
                "activation-allowed:false",
            ),
        )
    }
}
