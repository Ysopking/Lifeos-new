package app.lifeos.core.runtime.buildstudio

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityRequirement
import app.lifeos.core.runtime.capability.ToolPermission
import java.time.Instant

enum class BuildCapabilityChangeType {
    ADDED,
    UPDATED,
    REMOVED,
}

data class BuildCapabilityChange(
    val capabilityId: CapabilityId,
    val type: BuildCapabilityChangeType,
    val requiredInputs: Set<String> = emptySet(),
    val outputs: Set<String> = emptySet(),
) {
    init {
        require(requiredInputs.none { it.isBlank() })
        require(outputs.none { it.isBlank() })
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "build-capability-change/v1",
        capabilityId.value,
        type.name,
        *requiredInputs.sorted().map { "input:$it" }.toTypedArray(),
        *outputs.sorted().map { "output:$it" }.toTypedArray(),
    )
}

data class BuildPermissionDelta(
    val added: Set<ToolPermission> = emptySet(),
    val removed: Set<ToolPermission> = emptySet(),
) {
    init {
        require((added intersect removed).isEmpty()) {
            "Build permission cannot be both added and removed"
        }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "build-permission-delta/v1",
        *added.sortedBy { it.name }.map { "added:${it.name}" }.toTypedArray(),
        *removed.sortedBy { it.name }.map { "removed:${it.name}" }.toTypedArray(),
    )
}

data class BuildFileProvenance(
    val path: String,
    val operation: SourcePatchOperationType,
    val contentFingerprint: String?,
) {
    init {
        require(path.isSafeRepositoryPath()) { "Build provenance path must be safe" }
        if (operation == SourcePatchOperationType.DELETE) {
            require(contentFingerprint == null) { "Deleted provenance file cannot carry content fingerprint" }
        } else {
            require(!contentFingerprint.isNullOrBlank()) {
                "Created/updated provenance file requires content fingerprint"
            }
        }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "build-file-provenance/v1",
        path,
        operation.name,
        contentFingerprint.orEmpty(),
    )

    companion object {
        fun from(operation: SourcePatchOperation): BuildFileProvenance = BuildFileProvenance(
            path = operation.path,
            operation = operation.type,
            contentFingerprint = operation.contentFingerprint,
        )
    }
}

enum class BuildActorRole {
    REVIEWER,
    PROMOTION_ACTOR,
}

enum class BuildActorAction {
    REVIEWED,
    APPROVED,
    REJECTED,
    PROMOTED,
    ROLLED_BACK,
}

data class BuildActorEvidence(
    val actorId: String,
    val role: BuildActorRole,
    val action: BuildActorAction,
    val occurredAt: Instant,
    val evidenceRef: String,
) {
    init {
        require(actorId.isNotBlank()) { "Build provenance actor must not be blank" }
        require(evidenceRef.isNotBlank()) { "Build provenance actor requires evidence reference" }
        if (role == BuildActorRole.PROMOTION_ACTOR) {
            require(action == BuildActorAction.PROMOTED || action == BuildActorAction.ROLLED_BACK) {
                "Promotion actor evidence must describe promotion or rollback"
            }
        } else {
            require(action != BuildActorAction.PROMOTED && action != BuildActorAction.ROLLED_BACK) {
                "Reviewer evidence cannot claim promotion or rollback"
            }
        }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "build-actor-evidence/v1",
        actorId,
        role.name,
        action.name,
        occurredAt.toString(),
        evidenceRef,
    )
}

/**
 * Immutable J02 build provenance. It records what was built and verified but grants no authority to
 * activate or promote the resulting candidate.
 */
data class BuildProvenance(
    val sourceCommit: String,
    val sourceRequirement: CapabilityRequirement,
    val buildSpecId: String,
    val designSpecId: String,
    val patchPlanId: String,
    val branchName: String,
    val branchHeadCommit: String,
    val files: List<BuildFileProvenance>,
    val commandResults: List<BuildCommandResult>,
    val artifact: BuildArtifactEvidence,
    val capabilityChanges: List<BuildCapabilityChange>,
    val permissionDelta: BuildPermissionDelta = BuildPermissionDelta(),
    val actors: List<BuildActorEvidence> = emptyList(),
) {
    init {
        require(sourceCommit.matches(Regex("[0-9a-fA-F]{40}")))
        require(buildSpecId.isNotBlank() && designSpecId.isNotBlank() && patchPlanId.isNotBlank())
        require(branchName.isSafeBuildStudioBranchName())
        require(branchHeadCommit.matches(Regex("[0-9a-fA-F]{40}")))
        require(files.isNotEmpty()) { "Build provenance requires modified/generated files" }
        require(files.map { it.path }.distinct().size == files.size) {
            "Build provenance cannot contain duplicate file paths"
        }
        require(commandResults.map { it.command }.distinct().size == commandResults.size) {
            "Build provenance cannot contain duplicate gate commands"
        }
        require(BuildGateCommand.entries.all { required -> commandResults.any { it.command == required } }) {
            "Build provenance requires all J01 gate command results"
        }
        require(commandResults.all { it.success }) {
            "Candidate build provenance may only bind successful gate results"
        }
        require(capabilityChanges.isNotEmpty()) { "Build provenance requires explicit capability delta" }
        require(capabilityChanges.map { it.capabilityId to it.type }.distinct().size == capabilityChanges.size) {
            "Build provenance cannot duplicate a capability change"
        }
        val matchingChange = capabilityChanges.singleOrNull { change ->
            change.capabilityId == sourceRequirement.capabilityId &&
                change.type != BuildCapabilityChangeType.REMOVED
        }
        require(matchingChange != null) {
            "Build provenance capability delta must address source capability requirement"
        }
        require(matchingChange.requiredInputs.containsAll(sourceRequirement.requiredInputs)) {
            "Build provenance capability delta misses required inputs"
        }
        require(matchingChange.outputs.containsAll(sourceRequirement.requiredOutputs)) {
            "Build provenance capability delta misses required outputs"
        }
        require(actors.map { Triple(it.actorId, it.role, it.action) }.distinct().size == actors.size) {
            "Build provenance cannot duplicate actor actions"
        }
    }

    val id: String = StableFieldIds.fingerprint(
        "build-provenance/v1",
        sourceCommit.lowercase(),
        sourceRequirement.fingerprint(),
        buildSpecId,
        designSpecId,
        patchPlanId,
        branchName,
        branchHeadCommit.lowercase(),
        artifact.fingerprint(),
        permissionDelta.fingerprint(),
        *files.sortedBy { it.path }.map { "file:${it.fingerprint()}" }.toTypedArray(),
        *commandResults.sortedBy { it.command.name }.map { "command:${it.fingerprint()}" }.toTypedArray(),
        *capabilityChanges.sortedWith(compareBy<BuildCapabilityChange> { it.capabilityId.value }.thenBy { it.type.name })
            .map { "capability:${it.fingerprint()}" }.toTypedArray(),
        *actors.sortedWith(
            compareBy<BuildActorEvidence> { it.occurredAt }
                .thenBy { it.actorId }
                .thenBy { it.role.name }
                .thenBy { it.action.name }
        ).map { "actor:${it.fingerprint()}" }.toTypedArray(),
    )

    /** Provenance is evidence only. J02 never promotes or activates a candidate. */
    val activationAllowed: Boolean = false

    companion object {
        fun fromVerifiedCandidate(
            spec: BuildSpec,
            design: BuildDesignSpec,
            patch: SourcePatchPlan,
            candidate: BuildStudioCandidate,
            verification: BuildVerification,
            capabilityChanges: List<BuildCapabilityChange>,
            permissionDelta: BuildPermissionDelta = BuildPermissionDelta(),
            actors: List<BuildActorEvidence> = emptyList(),
        ): BuildProvenance {
            require(verification.status == BuildVerificationStatus.VERIFIED)
            require(candidate.buildSpecId == spec.id)
            require(candidate.designSpecId == design.id)
            require(candidate.patchPlanId == patch.id)
            require(candidate.verificationId == verification.id)
            require(candidate.branchName == verification.evidence.branchName)
            require(candidate.branchHeadCommit.equals(verification.evidence.branchHeadCommit, ignoreCase = true))
            require(verification.evidence.patchPlanId == patch.id)
            require(design.buildSpecId == spec.id)
            require(design.capability == spec.gap.requirement)
            require(patch.designSpecId == design.id)
            return BuildProvenance(
                sourceCommit = spec.sourceCommit,
                sourceRequirement = spec.gap.requirement,
                buildSpecId = spec.id,
                designSpecId = design.id,
                patchPlanId = patch.id,
                branchName = candidate.branchName,
                branchHeadCommit = candidate.branchHeadCommit,
                files = patch.operations.map { operation -> BuildFileProvenance.from(operation) },
                commandResults = verification.evidence.commandResults,
                artifact = requireNotNull(verification.evidence.artifact),
                capabilityChanges = capabilityChanges,
                permissionDelta = permissionDelta,
                actors = actors,
            )
        }
    }
}
