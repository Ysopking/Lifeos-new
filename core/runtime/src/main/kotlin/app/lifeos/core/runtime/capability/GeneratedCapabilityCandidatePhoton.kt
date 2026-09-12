package app.lifeos.core.runtime.capability

import app.lifeos.core.model.ModuleIdentity
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import app.lifeos.core.model.StableCognitiveIds
import app.lifeos.core.runtime.buildstudio.BuildStudioResult

/**
 * Canonical non-activating identity projection for generated candidates.
 * Promotion authority remains entirely outside this projection.
 */
object GeneratedCapabilityCandidatePhoton {
    const val MIME_TYPE = "application/vnd.lifeos.generated-capability-candidate+text"

    fun fromToolWorkshop(
        snapshot: ToolWorkshopJobSnapshot,
        sourcePhoton: Photon,
        gapPhotonId: PhotonId,
    ): Photon {
        val definition = snapshot.definition
        val implementationHash = snapshot.stageFingerprint
            ?: StableCognitiveIds.fingerprint("tool-workshop-candidate-implementation/v1", definition.id.value)
        val identity = ModuleIdentity(
            moduleId = snapshot.toolId,
            version = definition.workshopVersion,
            implementationHash = implementationHash,
            capabilityIds = setOf(definition.capabilityId.value),
        )
        return create(
            candidateKind = "TOOL_WORKSHOP",
            candidateId = definition.id.value,
            identity = identity,
            capabilityId = definition.capabilityId,
            requiredInputs = definition.requiredInputs,
            requiredOutputs = definition.requiredOutputs,
            state = snapshot.state.name,
            evidenceFingerprints = listOfNotNull(snapshot.stageFingerprint),
            sourcePhoton = sourcePhoton,
            gapPhotonId = gapPhotonId,
        )
    }

    fun fromBuildStudio(
        result: BuildStudioResult.CandidateReady,
        capabilityId: CapabilityId,
        requiredInputs: Set<String>,
        requiredOutputs: Set<String>,
        sourcePhoton: Photon,
        gapPhotonId: PhotonId,
        requestPhotonId: PhotonId,
    ): Photon {
        val candidate = result.candidate
        val artifact = requireNotNull(result.verification.evidence.artifact) {
            "Verified BuildStudio candidate must carry artifact evidence"
        }
        val identity = ModuleIdentity(
            moduleId = "buildstudio-${candidate.id.take(32)}",
            version = candidate.branchHeadCommit.lowercase(),
            implementationHash = artifact.debugApkSha256,
            capabilityIds = setOf(capabilityId.value),
        )
        return create(
            candidateKind = "BUILD_STUDIO",
            candidateId = candidate.id,
            identity = identity,
            capabilityId = capabilityId,
            requiredInputs = requiredInputs,
            requiredOutputs = requiredOutputs,
            state = "VERIFIED",
            evidenceFingerprints = listOf(
                result.verification.id,
                result.verification.evidence.fingerprint(),
                artifact.fingerprint(),
            ),
            sourcePhoton = sourcePhoton,
            gapPhotonId = gapPhotonId,
            extraParentIds = setOf(requestPhotonId),
        )
    }

    private fun create(
        candidateKind: String,
        candidateId: String,
        identity: ModuleIdentity,
        capabilityId: CapabilityId,
        requiredInputs: Set<String>,
        requiredOutputs: Set<String>,
        state: String,
        evidenceFingerprints: List<String>,
        sourcePhoton: Photon,
        gapPhotonId: PhotonId,
        extraParentIds: Set<PhotonId> = emptySet(),
    ): Photon {
        require(candidateId.isNotBlank() && state.isNotBlank())
        require(evidenceFingerprints.none { it.isBlank() })
        val contractFingerprint = StableCognitiveIds.fingerprint(
            "generated-candidate-contract/v1",
            capabilityId.value,
            *requiredInputs.sorted().map { "input:$it" }.toTypedArray(),
            *requiredOutputs.sorted().map { "output:$it" }.toTypedArray(),
        )
        val evidenceHash = StableCognitiveIds.fingerprint(
            "generated-candidate-evidence-set/v1",
            *evidenceFingerprints.sorted().toTypedArray(),
        )
        val fingerprint = StableCognitiveIds.fingerprint(
            "generated-capability-candidate-photon/v1",
            candidateKind,
            candidateId,
            identity.stableFingerprint,
            contractFingerprint,
            state,
            evidenceHash,
        )
        val parents = setOf(sourcePhoton.id, gapPhotonId) + extraParentIds
        return Photon(
            id = PhotonId("generated-candidate-${fingerprint.take(48)}"),
            content = buildString {
                appendLine("schema=1")
                appendLine("candidate_kind=$candidateKind")
                appendLine("candidate_id=$candidateId")
                appendLine("module_id=${identity.moduleId}")
                appendLine("module_version=${identity.version}")
                appendLine("implementation_hash=${identity.implementationHash}")
                appendLine("module_fingerprint=${identity.stableFingerprint}")
                appendLine("capability_id=${capabilityId.value}")
                appendLine("contract_fingerprint=$contractFingerprint")
                appendLine("state=$state")
                appendLine("evidence_fingerprint=$evidenceHash")
                append("activation_allowed=false")
            },
            mimeType = MIME_TYPE,
            semanticMass = 0.8,
            energy = 0.5,
            confidence = if (state == "VERIFIED" || state == ToolWorkshopJobState.TRIAL_READY.name) 1.0 else 0.75,
            provenance = Provenance(
                source = "generated-capability-candidate:${candidateKind.lowercase()}",
                actor = "lifeos",
                createdAt = sourcePhoton.provenance.createdAt,
                parentIds = parents,
            ),
            relations = parents.mapTo(linkedSetOf()) { parent ->
                PhotonRelation(parent, RelationType.DERIVED_FROM)
            },
            tags = setOf(
                "generated-capability-candidate",
                "candidate-kind:${candidateKind.lowercase()}",
                "capability:${capabilityId.value}",
                "module-fingerprint:${identity.stableFingerprint}",
                "candidate-contract:$contractFingerprint",
                "candidate-evidence:$evidenceHash",
                "activation-allowed:false",
            ),
        )
    }
}
