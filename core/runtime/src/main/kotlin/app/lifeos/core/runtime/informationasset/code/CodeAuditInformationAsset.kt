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

enum class CodeAuditSeverity { INFO, LOW, MEDIUM, HIGH, CRITICAL }

data class CodeLocation(
    val path: String,
    val startLine: Int? = null,
    val endLine: Int? = null,
) {
    init {
        require(path.isNotBlank()) { "Code location path must not be blank" }
        require(startLine == null || startLine > 0) { "Code location start line must be positive" }
        require(endLine == null || endLine > 0) { "Code location end line must be positive" }
        require(startLine == null || endLine == null || endLine >= startLine) {
            "Code location end line must not precede start line"
        }
    }
}

data class CodeAuditFinding(
    val key: String,
    val location: CodeLocation?,
    val severity: CodeAuditSeverity,
    val summary: String,
    val recommendation: String,
) {
    init {
        require(key.isNotBlank())
        require(summary.isNotBlank())
        require(recommendation.isNotBlank())
    }
}

object CodeAuditSemanticKeys {
    const val SCOPE = "code.audit.scope"
    const val TARGET = "code.audit.target"
    const val FINDING = "code.audit.finding"
    const val SEVERITY = "code.audit.severity"
    const val EVIDENCE = "code.audit.evidence"
    const val RECOMMENDATION = "code.audit.recommendation"
    val CORE: Set<String> = linkedSetOf(SCOPE, TARGET, FINDING, SEVERITY, EVIDENCE, RECOMMENDATION)
}

object CodeAuditInformationAssetFactory {
    fun request(namespace: String, stableKey: String, title: String): InformationAssetRequest =
        InformationAssetRequest.create(
            namespace = namespace,
            stableKey = stableKey,
            kind = InformationAssetKind.CODE_AUDIT,
            title = title,
            primaryDomainId = StandardInformationDomains.CODE,
            requiredSemanticKeys = CodeAuditSemanticKeys.CORE,
        )
}

class CodeAuditInformationAssetValidationProfile : InformationAssetValidationProfile {
    override val id: String = "lifeos.code-audit.v1"

    override fun validate(
        revision: InformationAssetRevision,
        evaluatedAt: Instant,
    ): List<InformationAssetValidationViolation> {
        if (revision.request.kind != InformationAssetKind.CODE_AUDIT) return emptyList()
        val evidenceById = revision.evidenceBindings.associateBy { it.id }
        return revision.claims
            .filter {
                it.semanticKey == CodeAuditSemanticKeys.FINDING &&
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
                            "Code audit finding requires code-domain evidence",
                        ))
                    } else if (codeEvidence.none { it.authority in ACCEPTED_FINDING_AUTHORITIES }) {
                        add(requirement(
                            "claims[${claim.id.value}].authority",
                            "Code audit finding cannot rely only on UNVERIFIED evidence",
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
        val ACCEPTED_FINDING_AUTHORITIES: Set<SourceAuthority> = setOf(
            SourceAuthority.USER_PROVIDED,
            SourceAuthority.DOCUMENTED,
            SourceAuthority.PRIMARY_SOURCE,
            SourceAuthority.OFFICIAL,
            SourceAuthority.AUTHORITATIVE,
        )
    }
}
