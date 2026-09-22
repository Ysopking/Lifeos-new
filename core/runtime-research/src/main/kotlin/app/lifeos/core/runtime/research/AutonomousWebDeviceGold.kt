package app.lifeos.core.runtime.research

import app.lifeos.core.runtime.buildstudio.WebAssistedBuildStudioPlan
import app.lifeos.core.runtime.web.OpenApiToolCandidate
import app.lifeos.core.runtime.web.WebApiCapabilityDiscoveryReport
import app.lifeos.core.runtime.web.WebAssistedToolWorkshopBrief
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class CapabilityExpansionGoldProof(
    val capabilityId: String,
    val discoveryReportFingerprint: String,
    val discoveryCandidateFingerprint: String,
    val toolCandidateFingerprint: String,
    val workshopBriefFingerprint: String,
    val buildStudioPlanFingerprint: String,
    val fingerprint: String,
) {
    init {
        require(capabilityId.isNotBlank())
        require(discoveryReportFingerprint.matches(SHA_256_REGEX_B420))
        require(discoveryCandidateFingerprint.matches(SHA_256_REGEX_B420))
        require(toolCandidateFingerprint.matches(SHA_256_REGEX_B420))
        require(workshopBriefFingerprint.matches(SHA_256_REGEX_B420))
        require(buildStudioPlanFingerprint.matches(SHA_256_REGEX_B420))
        require(
            fingerprint == capabilityExpansionFingerprint(
                capabilityId,
                discoveryReportFingerprint,
                discoveryCandidateFingerprint,
                toolCandidateFingerprint,
                workshopBriefFingerprint,
                buildStudioPlanFingerprint,
            )
        )
    }

    val activationAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false
    val permissionAuthority: Boolean get() = false

    companion object {
        fun from(
            discovery: WebApiCapabilityDiscoveryReport,
            toolCandidate: OpenApiToolCandidate,
            brief: WebAssistedToolWorkshopBrief,
            buildPlan: WebAssistedBuildStudioPlan,
        ): CapabilityExpansionGoldProof {
            require(
                discovery.candidates.any { it.fingerprint == toolCandidate.discoveryCandidateFingerprint }
            ) {
                "B420 requires the exact B412 discovery candidate used by B413"
            }
            require(brief.toolCandidateFingerprint == toolCandidate.fingerprint) {
                "B420 B414 brief must bind the exact B413 tool candidate"
            }
            require(brief.requirement.capabilityId.value == toolCandidate.capabilityId)
            require(buildPlan.design.capability.capabilityId.value == toolCandidate.capabilityId)
            require(
                buildPlan.design.implementationNotes.contains(
                    "web-assisted-tool-workshop-brief:" + brief.fingerprint
                )
            ) {
                "B420 B415 plan must bind the exact B414 brief"
            }
            require(
                buildPlan.design.implementationNotes.contains(
                    "openapi-tool-candidate:" + toolCandidate.fingerprint
                )
            ) {
                "B420 B415 plan must bind the exact B413 candidate"
            }

            val discoveryCandidateFingerprint = toolCandidate.discoveryCandidateFingerprint
            val fingerprint = capabilityExpansionFingerprint(
                capabilityId = toolCandidate.capabilityId,
                discoveryReportFingerprint = discovery.fingerprint,
                discoveryCandidateFingerprint = discoveryCandidateFingerprint,
                toolCandidateFingerprint = toolCandidate.fingerprint,
                workshopBriefFingerprint = brief.fingerprint,
                buildStudioPlanFingerprint = buildPlan.fingerprint,
            )
            return CapabilityExpansionGoldProof(
                capabilityId = toolCandidate.capabilityId,
                discoveryReportFingerprint = discovery.fingerprint,
                discoveryCandidateFingerprint = discoveryCandidateFingerprint,
                toolCandidateFingerprint = toolCandidate.fingerprint,
                workshopBriefFingerprint = brief.fingerprint,
                buildStudioPlanFingerprint = buildPlan.fingerprint,
                fingerprint = fingerprint,
            )
        }
    }
}

data class ActionOutcomeGoldProof(
    val actionGraphRevisionFingerprint: String,
    val outcomeLearningReportFingerprint: String,
    val outcomeLearningCandidateFingerprint: String,
    val dispatchPlanFingerprint: String,
    val resourceIdentity: String,
    val fingerprint: String,
) {
    init {
        require(actionGraphRevisionFingerprint.matches(SHA_256_REGEX_B420))
        require(outcomeLearningReportFingerprint.matches(SHA_256_REGEX_B420))
        require(outcomeLearningCandidateFingerprint.matches(SHA_256_REGEX_B420))
        require(dispatchPlanFingerprint.matches(SHA_256_REGEX_B420))
        require(resourceIdentity.isNotBlank())
        require(
            fingerprint == actionOutcomeFingerprint(
                actionGraphRevisionFingerprint,
                outcomeLearningReportFingerprint,
                outcomeLearningCandidateFingerprint,
                dispatchPlanFingerprint,
                resourceIdentity,
            )
        )
    }

    val causalAuthority: Boolean get() = false
    val truthAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false

    companion object {
        fun from(
            actionGraphRevisionFingerprint: String,
            report: ActionOutcomeLearningReport,
        ): ActionOutcomeGoldProof {
            require(report.evidence.state == ActionOutcomeLearningState.CONFIRMED_EVIDENCE) {
                "B420 productive action proof requires confirmed B416 outcome evidence"
            }
            val candidate = report.candidates.single()
            require(candidate.direction == ActionOutcomeLearningDirection.POSITIVE)
            require(candidate.nextCycleEligible)
            val fingerprint = actionOutcomeFingerprint(
                actionGraphRevisionFingerprint = actionGraphRevisionFingerprint,
                outcomeLearningReportFingerprint = report.fingerprint,
                outcomeLearningCandidateFingerprint = candidate.fingerprint,
                dispatchPlanFingerprint = candidate.dispatchPlanFingerprint,
                resourceIdentity = candidate.resourceIdentity,
            )
            return ActionOutcomeGoldProof(
                actionGraphRevisionFingerprint = actionGraphRevisionFingerprint,
                outcomeLearningReportFingerprint = report.fingerprint,
                outcomeLearningCandidateFingerprint = candidate.fingerprint,
                dispatchPlanFingerprint = candidate.dispatchPlanFingerprint,
                resourceIdentity = candidate.resourceIdentity,
                fingerprint = fingerprint,
            )
        }
    }
}

data class FailureLearningGoldProof(
    val failureReportFingerprint: String,
    val failureCandidateFingerprints: List<String>,
    val fingerprint: String,
) {
    init {
        require(failureReportFingerprint.matches(SHA_256_REGEX_B420))
        require(failureCandidateFingerprints.isNotEmpty())
        require(
            failureCandidateFingerprints ==
                failureCandidateFingerprints.distinct().sorted()
        )
        require(failureCandidateFingerprints.all { it.matches(SHA_256_REGEX_B420) })
        require(
            fingerprint == failureLearningFingerprint(
                failureReportFingerprint,
                failureCandidateFingerprints,
            )
        )
    }

    val retryAuthority: Boolean get() = false
    val credentialAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false

    companion object {
        fun from(report: WebFailureLearningReport): FailureLearningGoldProof {
            require(report.candidates.isNotEmpty()) {
                "B420 failure-learning proof requires at least one bounded B417 candidate"
            }
            val candidates = report.candidates.map { it.fingerprint }.distinct().sorted()
            return FailureLearningGoldProof(
                failureReportFingerprint = report.fingerprint,
                failureCandidateFingerprints = candidates,
                fingerprint = failureLearningFingerprint(report.fingerprint, candidates),
            )
        }
    }
}

data class WorkflowTransferGoldProof(
    val workflowInductionReportFingerprint: String,
    val transferCandidateFingerprint: String,
    val sourceSkillFingerprint: String,
    val sourceSurfaceFingerprint: String,
    val targetSurfaceFingerprint: String,
    val fingerprint: String,
) {
    init {
        require(workflowInductionReportFingerprint.matches(SHA_256_REGEX_B420))
        require(transferCandidateFingerprint.matches(SHA_256_REGEX_B420))
        require(sourceSkillFingerprint.matches(SHA_256_REGEX_B420))
        require(sourceSurfaceFingerprint.matches(SHA_256_REGEX_B420))
        require(targetSurfaceFingerprint.matches(SHA_256_REGEX_B420))
        require(
            fingerprint == workflowTransferFingerprint(
                workflowInductionReportFingerprint,
                transferCandidateFingerprint,
                sourceSkillFingerprint,
                sourceSurfaceFingerprint,
                targetSurfaceFingerprint,
            )
        )
    }

    val semanticIdentityAuthority: Boolean get() = false
    val activationAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false

    companion object {
        fun from(
            report: WorkflowSkillInductionReport,
            transfer: CrossSurfaceWorkflowTransferCandidate,
        ): WorkflowTransferGoldProof {
            require(transfer.inductionReportFingerprint == report.fingerprint)
            val source = report.candidates.singleOrNull {
                it.id == transfer.sourceSkillId &&
                    it.fingerprint() == transfer.sourceSkillFingerprint
            }
            require(source != null) {
                "B420 B419 transfer must bind an exact B418 source skill"
            }
            require(transfer.requiresShadowValidation)
            val sourceSurfaceFingerprint = transfer.sourceSurface.fingerprint()
            val targetSurfaceFingerprint = transfer.targetSurface.fingerprint()
            return WorkflowTransferGoldProof(
                workflowInductionReportFingerprint = report.fingerprint,
                transferCandidateFingerprint = transfer.fingerprint,
                sourceSkillFingerprint = source.fingerprint(),
                sourceSurfaceFingerprint = sourceSurfaceFingerprint,
                targetSurfaceFingerprint = targetSurfaceFingerprint,
                fingerprint = workflowTransferFingerprint(
                    report.fingerprint,
                    transfer.fingerprint,
                    source.fingerprint(),
                    sourceSurfaceFingerprint,
                    targetSurfaceFingerprint,
                ),
            )
        }
    }
}

data class AutonomousWebDeviceSemanticCheckpoint(
    val semanticVersion: Int,
    val capabilityExpansionProofFingerprint: String,
    val actionOutcomeProofFingerprint: String,
    val failureLearningProofFingerprint: String,
    val workflowTransferProofFingerprint: String,
    val semanticFingerprint: String,
    val processEpoch: String,
    val processCheckpointFingerprint: String,
) {
    init {
        require(semanticVersion > 0)
        require(capabilityExpansionProofFingerprint.matches(SHA_256_REGEX_B420))
        require(actionOutcomeProofFingerprint.matches(SHA_256_REGEX_B420))
        require(failureLearningProofFingerprint.matches(SHA_256_REGEX_B420))
        require(workflowTransferProofFingerprint.matches(SHA_256_REGEX_B420))
        require(processEpoch.isNotBlank())
        require(
            semanticFingerprint == semanticCheckpointFingerprint(
                semanticVersion,
                capabilityExpansionProofFingerprint,
                actionOutcomeProofFingerprint,
                failureLearningProofFingerprint,
                workflowTransferProofFingerprint,
            )
        )
        require(
            processCheckpointFingerprint == processCheckpointFingerprint(
                semanticFingerprint,
                processEpoch,
            )
        )
    }

    companion object {
        fun create(
            semanticVersion: Int = 1,
            capabilityExpansionProofFingerprint: String,
            actionOutcomeProofFingerprint: String,
            failureLearningProofFingerprint: String,
            workflowTransferProofFingerprint: String,
            processEpoch: String,
        ): AutonomousWebDeviceSemanticCheckpoint {
            val semanticFingerprint = semanticCheckpointFingerprint(
                semanticVersion,
                capabilityExpansionProofFingerprint,
                actionOutcomeProofFingerprint,
                failureLearningProofFingerprint,
                workflowTransferProofFingerprint,
            )
            return AutonomousWebDeviceSemanticCheckpoint(
                semanticVersion = semanticVersion,
                capabilityExpansionProofFingerprint = capabilityExpansionProofFingerprint,
                actionOutcomeProofFingerprint = actionOutcomeProofFingerprint,
                failureLearningProofFingerprint = failureLearningProofFingerprint,
                workflowTransferProofFingerprint = workflowTransferProofFingerprint,
                semanticFingerprint = semanticFingerprint,
                processEpoch = processEpoch,
                processCheckpointFingerprint = processCheckpointFingerprint(
                    semanticFingerprint,
                    processEpoch,
                ),
            )
        }
    }
}

data class AutonomousWebDeviceRecoveryGoldProof(
    val beforeSemanticFingerprint: String,
    val afterSemanticFingerprint: String,
    val beforeProcessCheckpointFingerprint: String,
    val afterProcessCheckpointFingerprint: String,
    val fingerprint: String,
) {
    init {
        require(beforeSemanticFingerprint.matches(SHA_256_REGEX_B420))
        require(afterSemanticFingerprint.matches(SHA_256_REGEX_B420))
        require(beforeProcessCheckpointFingerprint.matches(SHA_256_REGEX_B420))
        require(afterProcessCheckpointFingerprint.matches(SHA_256_REGEX_B420))
        require(beforeSemanticFingerprint == afterSemanticFingerprint)
        require(beforeProcessCheckpointFingerprint != afterProcessCheckpointFingerprint)
        require(
            fingerprint == recoveryFingerprint(
                beforeSemanticFingerprint,
                afterSemanticFingerprint,
                beforeProcessCheckpointFingerprint,
                afterProcessCheckpointFingerprint,
            )
        )
    }

    companion object {
        fun from(
            before: AutonomousWebDeviceSemanticCheckpoint,
            after: AutonomousWebDeviceSemanticCheckpoint,
        ): AutonomousWebDeviceRecoveryGoldProof {
            require(before.processEpoch != after.processEpoch) {
                "B420 recovery proof requires distinct process epochs"
            }
            require(before.semanticVersion == after.semanticVersion)
            require(before.semanticFingerprint == after.semanticFingerprint) {
                "B420 process death changed semantic Web+Device state"
            }
            return AutonomousWebDeviceRecoveryGoldProof(
                beforeSemanticFingerprint = before.semanticFingerprint,
                afterSemanticFingerprint = after.semanticFingerprint,
                beforeProcessCheckpointFingerprint = before.processCheckpointFingerprint,
                afterProcessCheckpointFingerprint = after.processCheckpointFingerprint,
                fingerprint = recoveryFingerprint(
                    before.semanticFingerprint,
                    after.semanticFingerprint,
                    before.processCheckpointFingerprint,
                    after.processCheckpointFingerprint,
                ),
            )
        }
    }
}

data class AutonomousWebDeviceGoldEvidence(
    val capabilityExpansion: CapabilityExpansionGoldProof,
    val actionOutcome: ActionOutcomeGoldProof,
    val failureLearning: FailureLearningGoldProof,
    val workflowTransfer: WorkflowTransferGoldProof,
    val recovery: AutonomousWebDeviceRecoveryGoldProof,
    val fingerprint: String,
) {
    init {
        require(
            fingerprint == goldEvidenceFingerprint(
                capabilityExpansion,
                actionOutcome,
                failureLearning,
                workflowTransfer,
                recovery,
            )
        )
    }

    val truthAuthority: Boolean get() = false
    val ownerPolicyAuthority: Boolean get() = false
    val permissionAuthority: Boolean get() = false
    val activationAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false

    companion object {
        fun create(
            capabilityExpansion: CapabilityExpansionGoldProof,
            actionOutcome: ActionOutcomeGoldProof,
            failureLearning: FailureLearningGoldProof,
            workflowTransfer: WorkflowTransferGoldProof,
            recovery: AutonomousWebDeviceRecoveryGoldProof,
        ): AutonomousWebDeviceGoldEvidence =
            AutonomousWebDeviceGoldEvidence(
                capabilityExpansion = capabilityExpansion,
                actionOutcome = actionOutcome,
                failureLearning = failureLearning,
                workflowTransfer = workflowTransfer,
                recovery = recovery,
                fingerprint = goldEvidenceFingerprint(
                    capabilityExpansion,
                    actionOutcome,
                    failureLearning,
                    workflowTransfer,
                    recovery,
                ),
            )
    }
}

class AutonomousWebDeviceGoldVerifier {
    fun verify(evidence: AutonomousWebDeviceGoldEvidence): Boolean =
        !evidence.truthAuthority &&
            !evidence.ownerPolicyAuthority &&
            !evidence.permissionAuthority &&
            !evidence.activationAuthority &&
            !evidence.executionAuthority &&
            evidence.recovery.beforeSemanticFingerprint ==
                evidence.recovery.afterSemanticFingerprint &&
            evidence.recovery.beforeProcessCheckpointFingerprint !=
                evidence.recovery.afterProcessCheckpointFingerprint
}

private fun capabilityExpansionFingerprint(
    capabilityId: String,
    discoveryReportFingerprint: String,
    discoveryCandidateFingerprint: String,
    toolCandidateFingerprint: String,
    workshopBriefFingerprint: String,
    buildStudioPlanFingerprint: String,
): String = b420Fingerprint(
    "capability-expansion-gold-proof/v1",
    capabilityId,
    discoveryReportFingerprint,
    discoveryCandidateFingerprint,
    toolCandidateFingerprint,
    workshopBriefFingerprint,
    buildStudioPlanFingerprint,
)

private fun actionOutcomeFingerprint(
    actionGraphRevisionFingerprint: String,
    outcomeLearningReportFingerprint: String,
    outcomeLearningCandidateFingerprint: String,
    dispatchPlanFingerprint: String,
    resourceIdentity: String,
): String = b420Fingerprint(
    "action-outcome-gold-proof/v1",
    actionGraphRevisionFingerprint,
    outcomeLearningReportFingerprint,
    outcomeLearningCandidateFingerprint,
    dispatchPlanFingerprint,
    resourceIdentity,
)

private fun failureLearningFingerprint(
    reportFingerprint: String,
    candidateFingerprints: List<String>,
): String = b420Fingerprint(
    "failure-learning-gold-proof/v1",
    reportFingerprint,
    *candidateFingerprints.sorted().toTypedArray(),
)

private fun workflowTransferFingerprint(
    reportFingerprint: String,
    transferCandidateFingerprint: String,
    sourceSkillFingerprint: String,
    sourceSurfaceFingerprint: String,
    targetSurfaceFingerprint: String,
): String = b420Fingerprint(
    "workflow-transfer-gold-proof/v1",
    reportFingerprint,
    transferCandidateFingerprint,
    sourceSkillFingerprint,
    sourceSurfaceFingerprint,
    targetSurfaceFingerprint,
)

private fun semanticCheckpointFingerprint(
    semanticVersion: Int,
    capabilityExpansionProofFingerprint: String,
    actionOutcomeProofFingerprint: String,
    failureLearningProofFingerprint: String,
    workflowTransferProofFingerprint: String,
): String = b420Fingerprint(
    "autonomous-web-device-semantic-checkpoint/v1",
    semanticVersion.toString(),
    capabilityExpansionProofFingerprint,
    actionOutcomeProofFingerprint,
    failureLearningProofFingerprint,
    workflowTransferProofFingerprint,
)

private fun processCheckpointFingerprint(
    semanticFingerprint: String,
    processEpoch: String,
): String = b420Fingerprint(
    "autonomous-web-device-process-checkpoint/v1",
    semanticFingerprint,
    processEpoch,
)

private fun recoveryFingerprint(
    beforeSemanticFingerprint: String,
    afterSemanticFingerprint: String,
    beforeProcessCheckpointFingerprint: String,
    afterProcessCheckpointFingerprint: String,
): String = b420Fingerprint(
    "autonomous-web-device-recovery-gold-proof/v1",
    beforeSemanticFingerprint,
    afterSemanticFingerprint,
    beforeProcessCheckpointFingerprint,
    afterProcessCheckpointFingerprint,
)

private fun goldEvidenceFingerprint(
    capabilityExpansion: CapabilityExpansionGoldProof,
    actionOutcome: ActionOutcomeGoldProof,
    failureLearning: FailureLearningGoldProof,
    workflowTransfer: WorkflowTransferGoldProof,
    recovery: AutonomousWebDeviceRecoveryGoldProof,
): String = b420Fingerprint(
    "autonomous-web-device-gold-evidence/v1",
    capabilityExpansion.fingerprint,
    actionOutcome.fingerprint,
    failureLearning.fingerprint,
    workflowTransfer.fingerprint,
    recovery.fingerprint,
)

private fun b420Fingerprint(domain: String, vararg parts: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun update(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(
            byteArrayOf(
                (bytes.size ushr 24).toByte(),
                (bytes.size ushr 16).toByte(),
                (bytes.size ushr 8).toByte(),
                bytes.size.toByte(),
            )
        )
        digest.update(bytes)
    }
    update(domain)
    parts.forEach(::update)
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private val SHA_256_REGEX_B420 = Regex("[0-9a-f]{64}")
