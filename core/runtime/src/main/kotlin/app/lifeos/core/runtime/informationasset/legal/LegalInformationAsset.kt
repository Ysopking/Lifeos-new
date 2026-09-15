package app.lifeos.core.runtime.informationasset.legal

import app.lifeos.core.field.SourceAuthority
import app.lifeos.core.runtime.informationasset.InformationAssetKind
import app.lifeos.core.runtime.informationasset.InformationAssetRequest
import app.lifeos.core.runtime.informationasset.InformationAssetRevision
import app.lifeos.core.runtime.informationasset.InformationAssetValidationCode
import app.lifeos.core.runtime.informationasset.InformationAssetValidationProfile
import app.lifeos.core.runtime.informationasset.InformationAssetValidationSeverity
import app.lifeos.core.runtime.informationasset.InformationAssetValidationViolation
import app.lifeos.core.runtime.informationasset.InformationClaimState
import app.lifeos.core.runtime.informationasset.InformationEvidenceBindingId
import app.lifeos.core.runtime.informationasset.StandardInformationDomains
import java.time.Instant

data class LegalJurisdiction(
    val code: String,
    val name: String,
) {
    init {
        require(code.isNotBlank()) { "Legal jurisdiction code must not be blank" }
        require(name.isNotBlank()) { "Legal jurisdiction name must not be blank" }
    }
}

enum class LegalNormHierarchy {
    CONSTITUTION,
    STATUTE,
    REGULATION,
    CASE_LAW,
    CONTRACT,
    POLICY,
    OTHER,
}

data class LegalNormReference(
    val identifier: String,
    val title: String,
    val jurisdiction: LegalJurisdiction,
    val hierarchy: LegalNormHierarchy,
    val version: String? = null,
    val effectiveFrom: Instant? = null,
    val effectiveUntilExclusive: Instant? = null,
    val evidenceBindingId: InformationEvidenceBindingId? = null,
) {
    init {
        require(identifier.isNotBlank()) { "Legal norm identifier must not be blank" }
        require(title.isNotBlank()) { "Legal norm title must not be blank" }
        require(version == null || version.isNotBlank()) { "Legal norm version must not be blank when present" }
        if (effectiveFrom != null && effectiveUntilExclusive != null) {
            require(effectiveUntilExclusive > effectiveFrom) {
                "Legal norm effectiveUntilExclusive must be after effectiveFrom"
            }
        }
    }

    fun isEffectiveAt(instant: Instant): Boolean =
        (effectiveFrom == null || !instant.isBefore(effectiveFrom)) &&
            (effectiveUntilExclusive == null || instant.isBefore(effectiveUntilExclusive))
}

enum class LegalFactState {
    EVIDENCED,
    ASSUMED,
    DISPUTED,
}

data class LegalFact(
    val semanticKey: String,
    val statement: String,
    val state: LegalFactState,
) {
    init {
        require(semanticKey.isNotBlank()) { "Legal fact semantic key must not be blank" }
        require(statement.isNotBlank()) { "Legal fact statement must not be blank" }
    }
}

data class LegalDeadline(
    val label: String,
    val dueAt: Instant,
    val basisNorm: LegalNormReference?,
    val explanation: String,
) {
    init {
        require(label.isNotBlank()) { "Legal deadline label must not be blank" }
        require(explanation.isNotBlank()) { "Legal deadline explanation must not be blank" }
    }
}

object LegalSemanticKeys {
    const val JURISDICTION = "legal.jurisdiction"
    const val ISSUE = "legal.issue"
    const val RULE = "legal.rule"
    const val APPLICATION = "legal.application"
    const val CONCLUSION = "legal.conclusion"
    const val FACT_PREFIX = "legal.fact."
    const val DEADLINE_PREFIX = "legal.deadline."

    val CORE: Set<String> = linkedSetOf(
        JURISDICTION,
        ISSUE,
        RULE,
        APPLICATION,
        CONCLUSION,
    )
}

object LegalInformationAssetFactory {
    fun request(
        namespace: String,
        stableKey: String,
        title: String,
    ): InformationAssetRequest = InformationAssetRequest.create(
        namespace = namespace,
        stableKey = stableKey,
        kind = InformationAssetKind.LEGAL,
        title = title,
        primaryDomainId = StandardInformationDomains.LEGAL,
        requiredSemanticKeys = LegalSemanticKeys.CORE,
    )
}

/**
 * Legal v1 deliberately validates source quality only for legal rule claims. Facts and application
 * may legitimately be supported by bridged organization/finance/science evidence, while the rule
 * itself must remain anchored in the legal domain and in a documented-or-stronger source class.
 */
class LegalInformationAssetValidationProfile : InformationAssetValidationProfile {
    override val id: String = "lifeos.legal.v1"

    override fun validate(
        revision: InformationAssetRevision,
        evaluatedAt: Instant,
    ): List<InformationAssetValidationViolation> {
        if (revision.request.kind != InformationAssetKind.LEGAL) return emptyList()

        val evidenceById = revision.evidenceBindings.associateBy { it.id }
        val violations = mutableListOf<InformationAssetValidationViolation>()

        revision.claims
            .filter {
                it.semanticKey == LegalSemanticKeys.RULE &&
                    it.state != InformationClaimState.ASSUMPTION &&
                    it.state != InformationClaimState.REJECTED
            }
            .forEach { claim ->
                val supporting = claim.evidenceBindingIds.mapNotNull(evidenceById::get)
                val legalEvidence = supporting.filter { it.domainId == StandardInformationDomains.LEGAL }

                if (legalEvidence.isEmpty()) {
                    violations += requirement(
                        path = "claims[${claim.id.value}].evidence",
                        message = "Legal rule claim requires evidence from the legal domain",
                    )
                } else if (legalEvidence.none { it.authority in ACCEPTED_RULE_AUTHORITIES }) {
                    violations += requirement(
                        path = "claims[${claim.id.value}].authority",
                        message = "Legal rule claim requires DOCUMENTED, PRIMARY_SOURCE, OFFICIAL, or AUTHORITATIVE evidence",
                    )
                }
            }

        return violations
    }

    private fun requirement(
        path: String,
        message: String,
    ): InformationAssetValidationViolation = InformationAssetValidationViolation(
        code = InformationAssetValidationCode.DOMAIN_PROFILE_REQUIREMENT,
        severity = InformationAssetValidationSeverity.ERROR,
        path = path,
        message = message,
    )

    companion object {
        val ACCEPTED_RULE_AUTHORITIES: Set<SourceAuthority> = setOf(
            SourceAuthority.DOCUMENTED,
            SourceAuthority.PRIMARY_SOURCE,
            SourceAuthority.OFFICIAL,
            SourceAuthority.AUTHORITATIVE,
        )
    }
}
