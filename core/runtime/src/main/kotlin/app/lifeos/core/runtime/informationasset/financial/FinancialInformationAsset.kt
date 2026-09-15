package app.lifeos.core.runtime.informationasset.financial

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
import java.math.BigDecimal
import java.time.Instant

data class FinancialAmount(
    val currencyCode: String,
    val decimalAmount: String,
) {
    init {
        require(currencyCode.matches(Regex("[A-Z]{3}"))) {
            "Financial currency code must be an uppercase ISO-style three-letter code"
        }
        require(runCatching { BigDecimal(decimalAmount) }.isSuccess) {
            "Financial decimal amount must be parseable"
        }
    }

    fun normalizedAmount(): String = BigDecimal(decimalAmount).stripTrailingZeros().toPlainString()
}

data class FinancialPeriod(
    val startInclusive: Instant,
    val endExclusive: Instant,
) {
    init {
        require(endExclusive > startInclusive) { "Financial period end must be after start" }
    }
}

data class FinancialMetric(
    val key: String,
    val label: String,
    val amount: FinancialAmount? = null,
    val ratio: Double? = null,
) {
    init {
        require(key.isNotBlank()) { "Financial metric key must not be blank" }
        require(label.isNotBlank()) { "Financial metric label must not be blank" }
        require((amount == null) xor (ratio == null)) {
            "Financial metric requires exactly one amount or ratio"
        }
        require(ratio == null || ratio.isFinite()) { "Financial ratio must be finite" }
    }
}

object FinancialSemanticKeys {
    const val PERIOD = "financial.period"
    const val SUBJECT = "financial.subject"
    const val METRIC = "financial.metric"
    const val VALUE = "financial.value"
    const val ASSUMPTIONS = "financial.assumptions"
    const val CONCLUSION = "financial.conclusion"

    val CORE: Set<String> = linkedSetOf(
        PERIOD,
        SUBJECT,
        METRIC,
        VALUE,
        ASSUMPTIONS,
        CONCLUSION,
    )
}

object FinancialInformationAssetFactory {
    fun request(
        namespace: String,
        stableKey: String,
        title: String,
    ): InformationAssetRequest = InformationAssetRequest.create(
        namespace = namespace,
        stableKey = stableKey,
        kind = InformationAssetKind.FINANCIAL,
        title = title,
        primaryDomainId = StandardInformationDomains.FINANCE,
        requiredSemanticKeys = FinancialSemanticKeys.CORE,
    )
}

class FinancialInformationAssetValidationProfile : InformationAssetValidationProfile {
    override val id: String = "lifeos.financial.v1"

    override fun validate(
        revision: InformationAssetRevision,
        evaluatedAt: Instant,
    ): List<InformationAssetValidationViolation> {
        if (revision.request.kind != InformationAssetKind.FINANCIAL) return emptyList()

        val evidenceById = revision.evidenceBindings.associateBy { it.id }
        return revision.claims
            .filter {
                it.semanticKey in EVIDENCE_CRITICAL_KEYS &&
                    it.state != InformationClaimState.ASSUMPTION &&
                    it.state != InformationClaimState.REJECTED
            }
            .flatMap { claim ->
                val financialEvidence = claim.evidenceBindingIds
                    .mapNotNull(evidenceById::get)
                    .filter { it.domainId == StandardInformationDomains.FINANCE }
                buildList {
                    if (financialEvidence.isEmpty()) {
                        add(requirement(
                            path = "claims[${claim.id.value}].evidence",
                            message = "Financial value or conclusion requires finance-domain evidence",
                        ))
                    } else if (financialEvidence.none { it.authority in ACCEPTED_AUTHORITIES }) {
                        add(requirement(
                            path = "claims[${claim.id.value}].authority",
                            message = "Financial value or conclusion cannot rely only on UNVERIFIED evidence",
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
            FinancialSemanticKeys.VALUE,
            FinancialSemanticKeys.CONCLUSION,
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
