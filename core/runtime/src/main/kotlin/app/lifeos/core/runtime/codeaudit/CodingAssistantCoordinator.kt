package app.lifeos.core.runtime.codeaudit

import app.lifeos.core.field.EvidenceReliability
import app.lifeos.core.field.SourceAuthority
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.TemporalValidity
import app.lifeos.core.runtime.buildstudio.BuildDesignSpec
import app.lifeos.core.runtime.buildstudio.BuildPathPolicy
import app.lifeos.core.runtime.buildstudio.BuildSpec
import app.lifeos.core.runtime.buildstudio.SourcePatchPlan
import app.lifeos.core.runtime.buildstudio.SourcePatchPlanner
import app.lifeos.core.runtime.informationasset.InformationAssetAssembler
import app.lifeos.core.runtime.informationasset.InformationAssetAssemblyRequest
import app.lifeos.core.runtime.informationasset.InformationAssetKind
import app.lifeos.core.runtime.informationasset.InformationAssetPhotonFactory
import app.lifeos.core.runtime.informationasset.InformationAssetRevision
import app.lifeos.core.runtime.informationasset.InformationAssetRevisionId
import app.lifeos.core.runtime.informationasset.InformationAssetValidationReport
import app.lifeos.core.runtime.informationasset.InformationAssetValidator
import app.lifeos.core.runtime.informationasset.InformationClaim
import app.lifeos.core.runtime.informationasset.InformationClaimState
import app.lifeos.core.runtime.informationasset.InformationEvidenceBinding
import app.lifeos.core.runtime.informationasset.PhotonRevisionReference
import app.lifeos.core.runtime.informationasset.StandardInformationDomains
import app.lifeos.core.runtime.informationasset.code.CodeChangeProposalInformationAssetFactory
import app.lifeos.core.runtime.informationasset.code.CodeChangeProposalInformationAssetValidationProfile
import app.lifeos.core.runtime.informationasset.code.CodeChangeProposalSemanticKeys
import app.lifeos.core.runtime.informationasset.code.CodeChangeRisk
import app.lifeos.core.runtime.informationasset.code.CodeChangeStep
import java.time.Instant

enum class CodingAssistantMode {
    AUDIT,
    PROPOSE,
    IMPLEMENT,
    VERIFY,
}

data class CodingProposalRequest(
    val namespace: String,
    val stableKey: String,
    val title: String,
    val intent: String,
    val steps: List<CodeChangeStep>,
    val risks: List<CodeChangeRisk>,
    val validationPlan: String,
    val rollbackPlan: String,
) {
    init {
        require(namespace.isNotBlank() && stableKey.isNotBlank() && title.isNotBlank())
        require(intent.isNotBlank()) { "Coding proposal intent must not be blank" }
        require(steps.isNotEmpty()) { "Coding proposal requires at least one change step" }
        require(validationPlan.isNotBlank()) { "Coding proposal validation plan must not be blank" }
        require(rollbackPlan.isNotBlank()) { "Coding proposal rollback plan must not be blank" }
    }

    val targetPaths: Set<String> = steps.mapTo(linkedSetOf()) { it.target.path }
}

data class CodingProposalResult(
    val sourceAuditRevisionId: InformationAssetRevisionId,
    val proposal: InformationAssetRevision,
    val validation: InformationAssetValidationReport,
    val targetPaths: Set<String>,
) {
    init {
        require(proposal.request.kind == InformationAssetKind.CODE_CHANGE_PROPOSAL)
        require(validation.revisionId == proposal.manifest.id)
        require(validation.isValid) { "Coding proposal result requires valid semantic evidence" }
        require(targetPaths.isNotEmpty())
    }

    val activationAllowed: Boolean = false
}

sealed interface CodingImplementationPreparation {
    data class Prepared(
        val sourceAuditRevisionId: InformationAssetRevisionId,
        val proposalRevisionId: InformationAssetRevisionId,
        val patchPlan: SourcePatchPlan,
    ) : CodingImplementationPreparation {
        val activationAllowed: Boolean = false
    }

    data class Rejected(val failures: List<String>) : CodingImplementationPreparation {
        init { require(failures.isNotEmpty() && failures.none { it.isBlank() }) }
    }
}

/**
 * Controlled coding assistant. PROPOSE turns a verified read-only audit into semantic change intent.
 * IMPLEMENT can only prepare a BuildStudio SourcePatchPlan and run the existing BuildPathPolicy;
 * source mutation/build/promotion/activation remain outside this coordinator.
 */
class CodingAssistantCoordinator(
    private val assembler: InformationAssetAssembler = InformationAssetAssembler(),
    private val validator: InformationAssetValidator = InformationAssetValidator(
        profiles = mapOf(
            InformationAssetKind.CODE_CHANGE_PROPOSAL to
                listOf(CodeChangeProposalInformationAssetValidationProfile()),
        ),
    ),
    private val photonFactory: InformationAssetPhotonFactory = InformationAssetPhotonFactory(),
    private val pathPolicy: BuildPathPolicy = BuildPathPolicy(),
) {
    fun propose(
        audit: CodeAuditRun,
        request: CodingProposalRequest,
        createdAt: Instant,
    ): CodingProposalResult {
        require(audit.validation.isValid) { "Coding proposal requires a valid source audit" }
        val auditPhoton = photonFactory.create(audit.revision, createdAt)
        val auditEvidence = InformationEvidenceBinding.create(
            source = PhotonRevisionReference.from(auditPhoton),
            fieldEvidenceId = null,
            domainId = StandardInformationDomains.CODE,
            authority = SourceAuthority.DOCUMENTED,
            confidence = 1.0,
            reliability = EvidenceReliability(1.0, "Deterministic LIFEOS code audit revision"),
            validity = TemporalValidity.UNBOUNDED,
            observedAt = createdAt,
            payloadFingerprint = StableFieldIds.fingerprint(
                "coding-assistant-audit-evidence/v1",
                audit.revision.request.id.value,
                audit.revision.manifest.id.value,
                audit.revision.manifest.stateHash.value,
                audit.snapshot.fingerprint,
            ),
        )

        fun supported(key: String, statement: String): InformationClaim = InformationClaim.create(
            domainId = StandardInformationDomains.CODE,
            semanticKey = key,
            statement = statement,
            state = InformationClaimState.SUPPORTED,
            confidence = 1.0,
            evidenceBindingIds = setOf(auditEvidence.id),
            explanation = "Grounded in exact deterministic code audit revision",
        )
        fun proposalClaim(key: String, statement: String): InformationClaim = InformationClaim.create(
            domainId = StandardInformationDomains.CODE,
            semanticKey = key,
            statement = statement,
            state = InformationClaimState.ASSUMPTION,
            confidence = 0.8,
            evidenceBindingIds = emptySet(),
            explanation = "Proposed future change; not evidence that execution occurred",
        )

        val targetStatement = request.steps
            .sortedBy { it.target.path }
            .joinToString("; ") { step ->
                buildString {
                    append(step.operation.name)
                    append(' ')
                    append(step.target.path)
                    step.target.symbol?.let { append('#').append(it) }
                }
            }
        val changeStatement = request.steps
            .joinToString("; ") { "${it.operation.name}:${it.target.path}:${it.description}" }
        val riskStatement = if (request.risks.isEmpty()) {
            "No additional proposal-specific risk declared; BuildStudio and audit gates remain required"
        } else {
            request.risks.joinToString("; ") { "${it.level.name}:${it.description} -> ${it.mitigation}" }
        }

        val claims = listOf(
            supported(CodeChangeProposalSemanticKeys.TARGET, targetStatement),
            proposalClaim(CodeChangeProposalSemanticKeys.INTENT, request.intent),
            proposalClaim(CodeChangeProposalSemanticKeys.CHANGE, changeStatement),
            proposalClaim(CodeChangeProposalSemanticKeys.RISK, riskStatement),
            supported(
                CodeChangeProposalSemanticKeys.VALIDATION,
                "Audit ${audit.revision.manifest.id.value} is valid; implementation still requires: ${request.validationPlan}",
            ),
            proposalClaim(CodeChangeProposalSemanticKeys.ROLLBACK, request.rollbackPlan),
        )
        val revision = assembler.assemble(
            InformationAssetAssemblyRequest(
                request = CodeChangeProposalInformationAssetFactory.request(
                    request.namespace,
                    request.stableKey,
                    request.title,
                ),
                sourcePhotons = listOf(auditPhoton),
                evidenceBindings = listOf(auditEvidence),
                claims = claims,
                participatingModules = setOf("coding-assistant-v1", "code-auditor-v1"),
            )
        ).revision
        val validation = validator.validate(revision, createdAt)
        require(validation.isValid) {
            "Coding proposal failed semantic validation: " +
                validation.violations.joinToString { "${it.code}:${it.path}" }
        }
        return CodingProposalResult(
            sourceAuditRevisionId = audit.revision.manifest.id,
            proposal = revision,
            validation = validation,
            targetPaths = request.targetPaths,
        )
    }

    suspend fun prepareImplementation(
        audit: CodeAuditRun,
        proposal: CodingProposalResult,
        spec: BuildSpec,
        design: BuildDesignSpec,
        patchPlanner: SourcePatchPlanner,
    ): CodingImplementationPreparation {
        val preflight = mutableListOf<String>()
        if (proposal.sourceAuditRevisionId != audit.revision.manifest.id) {
            preflight += "proposal-audit-revision-mismatch"
        }
        if (!spec.sourceCommit.equals(audit.snapshot.sourceCommit, ignoreCase = true)) {
            preflight += "build-source-commit-does-not-match-audit"
        }
        if (design.buildSpecId != spec.id) preflight += "design-build-spec-mismatch"
        val plannedPaths = design.plannedSourcePaths + design.plannedTestPaths
        val unapprovedPlannedPaths = plannedPaths - proposal.targetPaths
        unapprovedPlannedPaths.sorted().forEach { preflight += "proposal-path-not-approved:$it" }
        if (preflight.isNotEmpty()) return CodingImplementationPreparation.Rejected(preflight.distinct().sorted())

        val patch = patchPlanner.plan(design)
        val touchedPaths = patch.operations.mapTo(linkedSetOf()) { it.path }
        val unapprovedTouchedPaths = touchedPaths - proposal.targetPaths
        unapprovedTouchedPaths.sorted().forEach { preflight += "patch-path-not-approved:$it" }
        preflight += pathPolicy.validate(spec, design, patch)
        if (preflight.isNotEmpty()) {
            return CodingImplementationPreparation.Rejected(preflight.distinct().sorted())
        }

        return CodingImplementationPreparation.Prepared(
            sourceAuditRevisionId = audit.revision.manifest.id,
            proposalRevisionId = proposal.proposal.manifest.id,
            patchPlan = patch,
        )
    }
}
