package app.lifeos.core.runtime.informationasset.code

import app.lifeos.core.field.SourceAuthority
import app.lifeos.core.runtime.informationasset.InformationAssetKind
import app.lifeos.core.runtime.informationasset.InformationAssetRequest
import app.lifeos.core.runtime.informationasset.InformationAssetRevision
import app.lifeos.core.runtime.informationasset.InformationAssetValidationCode
import app.lifeos.core.runtime.informationasset.InformationAssetValidationProfile
import app.lifeos.core.runtime.informationasset.InformationAssetValidationSeverity
import app.lifeos.core.runtime.informationasset.InformationAssetValidationViolation
import app.lifeos.core.runtime.informationasset.InformationClaimState
import app.lifeos.core.runtime.informationasset.StandardInformationDomains
import java.time.Instant

enum class CodeChangeOperation {
    ADD,
    MODIFY,
    DELETE,
    RENAME,
    REFACTOR,
    CONFIGURE,
}

data class CodeChangeTarget(
    val path: String,
    val symbol: String? = null,
) {
    init {
        require(path.isNotBlank()) { "Code change target path must not be blank" }
        require(symbol == null || symbol.isNotBlank()) { "Code change target symbol must not be blank when present" }
    }
}

data class CodeChangeStep(
    val operation: CodeChangeOperation,
    val target: CodeChangeTarget,
    val description: String,
) {
    init { require(description.isNotBlank()) { "Code change description must not be blank" } }
}

enum class CodeChangeRiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
}

data class CodeChangeRisk(
    val level: CodeChangeRiskLevel,
    val description: String,
    val mitigation: String,
) {
    init {
        require(description.isNotBlank()) { "Code change risk description must not be blank" }
        require(mitigation.isNotBlank()) { "Code change risk mitigation must not be blank" }
    }
}

object CodeChangeProposalSemanticKeys {
    const val TARGET = "code.change.target"
    const val INTENT = "code.change.intent"
    const val CHANGE = "code.change.proposal"
    const val RISK = "code.change.risk"
    const val VALIDATION = "code.change.validation"
    const val ROLLBACK = "code.change.rollback"

    val CORE: Set<String> = linkedSetOf(TARGET, INTENT, CHANGE, RISK, VALIDATION, ROLLBACK)
}

object CodeChangeProposalInformationAssetFactory {
    fun request(namespace: String, stableKey: String, title: String): InformationAssetRequest =
        InformationAssetRequest.create(
            namespace = namespace,
            stableKey = stableKey,
            kind = InformationAssetKind.CODE_CHANGE_PROPOSAL,
            title = title,
            primaryDomainId = StandardInformationDomains.CODE,
            requiredSemanticKeys = CodeChangeProposalSemanticKeys.CORE,
        )
}

class CodeChangeProposalInformationAssetValidationProfile : InformationAssetValidationProfile {
    override val id: String = "lifeos.code-change-proposal.v1"

    override fun validate(
        revision: InformationAssetRevision,
        evaluatedAt: Instant,
    ): List<InformationAssetValidationViolation> {
        if (revision.request.kind != InformationAssetKind.CODE_CHANGE_PROPOSAL) return emptyList()
        val evidenceById = revision.evidenceBindings.associateBy { it.id }
        return revision.claims
            .filter {
                it.semanticKey in EVIDENCE_CRITICAL_KEYS &&
                    it.state != InformationClaimState.ASSUMPTION &&
                    it.state != InformationClaimState.REJECTED
            }
            .flatMap { claim ->
                val codeEvidence = claim.evidenceBindingIds
                    .mapNotNull(evidenceById::get)
                    .filter { it.domainId == StandardInformationDomains.CODE }
                buildList {
                    if (codeEvidence.isEmpty()) {
                        add(requirement(
                            "claims[${claim.id.value}].evidence",
                            "Code change target or validation claim requires code-domain evidence",
                        ))
                    } else if (codeEvidence.none { it.authority in ACCEPTED_AUTHORITIES }) {
                        add(requirement(
                            "claims[${claim.id.value}].authority",
                            "Code change target or validation claim cannot rely only on UNVERIFIED evidence",
                        ))
                    }
                }
            }
    }

    private fun requirement(path: String, message: String) = InformationAssetValidationViolation(
        code = InformationAssetValidationCode.DOMAIN_PROFILE_REQUIREMENT,
        severity = InformationAssetValidationSeverity.ERROR,
        path = path,
        message = message,
    )

    companion object {
        val EVIDENCE_CRITICAL_KEYS: Set<String> = setOf(
            CodeChangeProposalSemanticKeys.TARGET,
            CodeChangeProposalSemanticKeys.VALIDATION,
        )
        val ACCEPTED_AUTHORITIES: Set<SourceAuthority> = setOf(
            SourceAuthority.USER_PROVIDED,
            SourceAuthority.DOCUMENTED,
            SourceAuthority.PRIMARY_SOURCE,
            SourceAuthority.OFFICIAL,
            SourceAuthority.AUTHORITATIVE,
        )
    }
}
