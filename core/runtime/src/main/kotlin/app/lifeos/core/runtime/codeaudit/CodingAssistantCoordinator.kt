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

enum class CodingAssistantMode { AUDIT, PROPOSE, IMPLEMENT, VERIFY }

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
        require(intent.isNotBlank())
        require(steps.isNotEmpty())
        require(validationPlan.isNotBlank())
        require(rollbackPlan.isNotBlank())
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
        require(validation.isValid)
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
 * Controlled coding assistant. It can propose a future change and prepare a policy-checked patch
 * plan, but it never applies source changes, creates a production branch, builds, promotes or
 * activates code. Those authorities remain in BuildStudio and the existing promotion/Hot-Swap path.
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
        val evidence = InformationEvidenceBinding.create(
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

        fun supported(key: String, statement: String) = InformationClaim.create(
            domainId = StandardInformationDomains.CODE,
            semanticKey = key,
            statement = statement,
            state = InformationClaimState.SUPPORTED,
            confidence = 1.0,
            evidenceBindingIds = setOf(evidence.id),
            explanation = "Grounded in exact deterministic code audit revision",
        )
        fun future(key: String, statement: String) = InformationClaim.create(
            domainId = StandardInformationDomains.CODE,
            semanticKey = key,
            statement = statement,
            state = InformationClaimState.ASSUMPTION,
            confidence = 0.8,
            evidenceBindingIds = emptySet(),
            explanation = "Proposed future change; not evidence that execution occurred",
        )

        val targets = request.steps.sortedBy { it.target.path }.joinToString("; ") {
            "${it.operation.name} ${it.target.path}${it.target.symbol?.let { symbol -> "#$symbol" }.orEmpty()}"
        }
        val changes = request.steps.joinToString("; ") {
            "${it.operation.name}:${it.target.path}:${it.description}"
        }
        val risks = request.risks.takeIf { it.isNotEmpty() }
            ?.joinToString("; ") { "${it.level.name}:${it.description}->${it.mitigation}" }
            ?: "No additional proposal-specific risk declared; BuildStudio gates remain required"
        val claims = listOf(
            supported(CodeChangeProposalSemanticKeys.TARGET, targets),
            future(CodeChangeProposalSemanticKeys.INTENT, request.intent),
            future(CodeChangeProposalSemanticKeys.CHANGE, changes),
            future(CodeChangeProposalSemanticKeys.RISK, risks),
            supported(
                CodeChangeProposalSemanticKeys.VALIDATION,
                "Audit ${audit.revision.manifest.id.value} valid; implementation requires ${request.validationPlan}",
            ),
            future(CodeChangeProposalSemanticKeys.ROLLBACK, request.rollbackPlan),
        )
        val revision = assembler.assemble(
            InformationAssetAssemblyRequest(
                request = CodeChangeProposalInformationAssetFactory.request(
                    request.namespace,
                    request.stableKey,
                    request.title,
                ),
                sourcePhotons = listOf(auditPhoton),
                evidenceBindings = listOf(evidence),
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
        val failures = mutableListOf<String>()
        if (proposal.sourceAuditRevisionId != audit.revision.manifest.id) {
            failures += "proposal-audit-revision-mismatch"
        }
        if (!spec.sourceCommit.equals(audit.snapshot.sourceCommit, ignoreCase = true)) {
            failures += "build-source-commit-does-not-match-audit"
        }
        if (design.buildSpecId != spec.id) failures += "design-build-spec-mismatch"
        val plannedPaths = design.plannedSourcePaths + design.plannedTestPaths
        (plannedPaths - proposal.targetPaths).sorted().forEach {
            failures += "proposal-path-not-approved:$it"
        }
        if (failures.isNotEmpty()) return CodingImplementationPreparation.Rejected(failures.distinct().sorted())

        val patch = patchPlanner.plan(design)
        val touched = patch.operations.mapTo(linkedSetOf()) { it.path }
        (touched - proposal.targetPaths).sorted().forEach { failures += "patch-path-not-approved:$it" }
        failures += pathPolicy.validate(spec, design, patch)
        if (failures.isNotEmpty()) {
            return CodingImplementationPreparation.Rejected(failures.distinct().sorted())
        }
        return CodingImplementationPreparation.Prepared(
            sourceAuditRevisionId = audit.revision.manifest.id,
            proposalRevisionId = proposal.proposal.manifest.id,
            patchPlan = patch,
        )
    }
}
