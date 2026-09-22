package app.lifeos.core.runtime.research

import app.lifeos.core.runtime.level7.StructuralSignature
import app.lifeos.core.runtime.level7.StructuralTransferCandidate
import app.lifeos.core.runtime.reasoning.ProceduralSkillCandidate
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class WorkflowExecutionContextKind {
    WEB_SITE,
    ANDROID_APP,
    DESKTOP_APP,
    SERVICE_API,
    FILE_SYSTEM,
    OTHER,
}

data class WorkflowExecutionContext(
    val kind: WorkflowExecutionContextKind,
    val identityFingerprint: String,
    val contractFingerprint: String,
    val fingerprint: String,
) {
    init {
        require(identityFingerprint.matches(SHA_256_REGEX_B419))
        require(contractFingerprint.matches(SHA_256_REGEX_B419))
        require(
            fingerprint == b419Fingerprint(
                "workflow-execution-context/v1",
                kind.name,
                identityFingerprint,
                contractFingerprint,
            )
        )
    }

    companion object {
        fun create(
            kind: WorkflowExecutionContextKind,
            identityFingerprint: String,
            contractFingerprint: String,
        ): WorkflowExecutionContext =
            WorkflowExecutionContext(
                kind = kind,
                identityFingerprint = identityFingerprint,
                contractFingerprint = contractFingerprint,
                fingerprint = b419Fingerprint(
                    "workflow-execution-context/v1",
                    kind.name,
                    identityFingerprint,
                    contractFingerprint,
                ),
            )
    }
}

data class WorkflowStructureSignature(
    val topologyFingerprint: String,
    val relationFingerprint: String,
    val dimensionFingerprint: String,
    val fingerprint: String,
) {
    init {
        require(topologyFingerprint.matches(SHA_256_REGEX_B419))
        require(relationFingerprint.matches(SHA_256_REGEX_B419))
        require(dimensionFingerprint.matches(SHA_256_REGEX_B419))
        require(
            fingerprint == b419Fingerprint(
                "workflow-structure-signature/v1",
                topologyFingerprint,
                relationFingerprint,
                dimensionFingerprint,
            )
        )
    }

    companion object {
        fun from(candidate: ProceduralSkillCandidate): WorkflowStructureSignature {
            val relation = b419Fingerprint(
                "workflow-step-relations/v1",
                *candidate.steps
                    .sortedBy { it.key }
                    .map { step ->
                        step.key + ":" + step.dependencyKeys.sorted().joinToString(",")
                    }
                    .toTypedArray(),
            )
            val dimensions = b419Fingerprint(
                "workflow-step-dimensions/v1",
                *candidate.steps
                    .sortedBy { it.key }
                    .map { step ->
                        step.key + ":" + step.priority + ":" + step.objective
                    }
                    .toTypedArray(),
            )
            return WorkflowStructureSignature(
                topologyFingerprint = candidate.shapeFingerprint,
                relationFingerprint = relation,
                dimensionFingerprint = dimensions,
                fingerprint = b419Fingerprint(
                    "workflow-structure-signature/v1",
                    candidate.shapeFingerprint,
                    relation,
                    dimensions,
                ),
            )
        }
    }
}

data class TargetWorkflowStructureEvidence(
    val context: WorkflowExecutionContext,
    val structure: WorkflowStructureSignature,
    val evidenceFingerprint: String,
    val verified: Boolean,
    val fingerprint: String,
) {
    init {
        require(evidenceFingerprint.matches(SHA_256_REGEX_B419))
        require(verified) { "B419 target structure evidence must be explicitly verified" }
        require(
            fingerprint == b419Fingerprint(
                "target-workflow-structure-evidence/v1",
                context.fingerprint,
                structure.fingerprint,
                evidenceFingerprint,
                verified.toString(),
            )
        )
    }

    val executionAuthority: Boolean get() = false
    val activationAuthority: Boolean get() = false
    val promotionAuthority: Boolean get() = false

    companion object {
        fun create(
            context: WorkflowExecutionContext,
            structure: WorkflowStructureSignature,
            evidenceFingerprint: String,
            verified: Boolean = true,
        ): TargetWorkflowStructureEvidence =
            TargetWorkflowStructureEvidence(
                context = context,
                structure = structure,
                evidenceFingerprint = evidenceFingerprint,
                verified = verified,
                fingerprint = b419Fingerprint(
                    "target-workflow-structure-evidence/v1",
                    context.fingerprint,
                    structure.fingerprint,
                    evidenceFingerprint,
                    verified.toString(),
                ),
            )
    }
}

data class CrossContextWorkflowTransferHypothesis(
    val sourceReportFingerprint: String,
    val sourceCandidateId: String,
    val sourceCandidateFingerprint: String,
    val sourceContextFingerprint: String,
    val targetContextFingerprint: String,
    val targetEvidenceFingerprint: String,
    val structuralTransferCandidateId: String,
    val structuralTransferCandidateFingerprint: String,
    val fingerprint: String,
) {
    init {
        require(sourceReportFingerprint.matches(SHA_256_REGEX_B419))
        require(sourceCandidateId.startsWith(ProceduralSkillCandidate.ID_PREFIX))
        require(sourceCandidateFingerprint.matches(SHA_256_REGEX_B419))
        require(sourceContextFingerprint.matches(SHA_256_REGEX_B419))
        require(targetContextFingerprint.matches(SHA_256_REGEX_B419))
        require(sourceContextFingerprint != targetContextFingerprint)
        require(targetEvidenceFingerprint.matches(SHA_256_REGEX_B419))
        require(structuralTransferCandidateId.startsWith("structural-transfer:"))
        require(structuralTransferCandidateFingerprint.matches(SHA_256_REGEX_B419))
        require(
            fingerprint == b419Fingerprint(
                "cross-context-workflow-transfer-hypothesis/v1",
                sourceReportFingerprint,
                sourceCandidateId,
                sourceCandidateFingerprint,
                sourceContextFingerprint,
                targetContextFingerprint,
                targetEvidenceFingerprint,
                structuralTransferCandidateId,
                structuralTransferCandidateFingerprint,
            )
        )
    }

    val semanticIdentityEstablished: Boolean get() = false
    val directActivationAllowed: Boolean get() = false
    val executionAuthority: Boolean get() = false
    val promotionAuthority: Boolean get() = false
    val ownerPolicyAuthority: Boolean get() = false
}

/**
 * B419 transfers only an already-induced B418 workflow shape across execution contexts.
 *
 * Transfer requires exact structural equality proven by explicit target evidence. Equality of
 * structure is not semantic identity, permission, provider compatibility or execution authority.
 * The existing Level7 StructuralTransferCandidate remains the structural carrier and stays
 * non-activating.
 */
class CrossContextWorkflowTransferEngine {
    fun propose(
        sourceReport: WorkflowSkillInductionReport,
        sourceCandidate: ProceduralSkillCandidate,
        sourceContext: WorkflowExecutionContext,
        targetEvidence: TargetWorkflowStructureEvidence,
    ): CrossContextWorkflowTransferHypothesis? {
        require(
            sourceReport.candidates.any {
                it.id == sourceCandidate.id && it.fingerprint() == sourceCandidate.fingerprint()
            }
        ) {
            "B419 source candidate is not bound to the supplied B418 report"
        }
        require(!sourceCandidate.executionAuthority)
        require(!sourceCandidate.activationAllowed)
        require(!sourceCandidate.promotionAllowed)
        require(sourceContext.fingerprint != targetEvidence.context.fingerprint) {
            "Cross-context transfer requires a distinct target context"
        }

        val sourceStructure = WorkflowStructureSignature.from(sourceCandidate)
        if (sourceStructure != targetEvidence.structure) return null

        val validationFingerprint = b419Fingerprint(
            "cross-context-workflow-transfer-validation/v1",
            sourceReport.fingerprint,
            sourceCandidate.fingerprint(),
            sourceContext.fingerprint,
            targetEvidence.fingerprint,
        )

        val sourceSignature = StructuralSignature(
            domainId = "workflow-context:" + sourceContext.fingerprint,
            topologyFingerprint = sourceStructure.topologyFingerprint,
            relationFingerprint = sourceStructure.relationFingerprint,
            dimensionFingerprint = sourceStructure.dimensionFingerprint,
        )
        val targetSignature = StructuralSignature(
            domainId = "workflow-context:" + targetEvidence.context.fingerprint,
            topologyFingerprint = targetEvidence.structure.topologyFingerprint,
            relationFingerprint = targetEvidence.structure.relationFingerprint,
            dimensionFingerprint = targetEvidence.structure.dimensionFingerprint,
        )
        val structuralCandidate = StructuralTransferCandidate.create(
            source = sourceSignature,
            target = targetSignature,
            structuralSimilarity = 1.0,
            validationFingerprint = validationFingerprint,
        )
        require(!structuralCandidate.semanticIdentityEstablished)
        require(!structuralCandidate.directTransferActivationAllowed)

        return CrossContextWorkflowTransferHypothesis(
            sourceReportFingerprint = sourceReport.fingerprint,
            sourceCandidateId = sourceCandidate.id,
            sourceCandidateFingerprint = sourceCandidate.fingerprint(),
            sourceContextFingerprint = sourceContext.fingerprint,
            targetContextFingerprint = targetEvidence.context.fingerprint,
            targetEvidenceFingerprint = targetEvidence.fingerprint,
            structuralTransferCandidateId = structuralCandidate.id,
            structuralTransferCandidateFingerprint = structuralCandidate.fingerprint(),
            fingerprint = b419Fingerprint(
                "cross-context-workflow-transfer-hypothesis/v1",
                sourceReport.fingerprint,
                sourceCandidate.id,
                sourceCandidate.fingerprint(),
                sourceContext.fingerprint,
                targetEvidence.context.fingerprint,
                targetEvidence.fingerprint,
                structuralCandidate.id,
                structuralCandidate.fingerprint(),
            ),
        )
    }
}

private fun b419Fingerprint(domain: String, vararg parts: String): String {
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

private val SHA_256_REGEX_B419 = Regex("[0-9a-f]{64}")
