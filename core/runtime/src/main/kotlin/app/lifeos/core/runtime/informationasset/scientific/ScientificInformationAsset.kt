package app.lifeos.core.runtime.informationasset.scientific

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

enum class ScientificStudyDesign {
    OBSERVATIONAL,
    EXPERIMENTAL,
    RANDOMIZED_CONTROLLED,
    SYSTEMATIC_REVIEW,
    META_ANALYSIS,
    MODELING,
    OTHER,
}

data class ScientificMeasurement(
    val variable: String,
    val value: String,
    val unit: String?,
    val uncertainty: String?,
) {
    init {
        require(variable.isNotBlank()) { "Scientific measurement variable must not be blank" }
        require(value.isNotBlank()) { "Scientific measurement value must not be blank" }
        require(unit == null || unit.isNotBlank()) { "Scientific measurement unit must not be blank when present" }
        require(uncertainty == null || uncertainty.isNotBlank()) {
            "Scientific uncertainty must not be blank when present"
        }
    }
}

data class ScientificMethod(
    val design: ScientificStudyDesign,
    val description: String,
    val sampleDescription: String?,
) {
    init {
        require(description.isNotBlank()) { "Scientific method description must not be blank" }
        require(sampleDescription == null || sampleDescription.isNotBlank()) {
            "Scientific sample description must not be blank when present"
        }
    }
}

object ScientificSemanticKeys {
    const val QUESTION = "scientific.question"
    const val HYPOTHESIS = "scientific.hypothesis"
    const val METHOD = "scientific.method"
    const val EVIDENCE = "scientific.evidence"
    const val RESULT = "scientific.result"
    const val LIMITATIONS = "scientific.limitations"

    val CORE: Set<String> = linkedSetOf(
        QUESTION,
        HYPOTHESIS,
        METHOD,
        EVIDENCE,
        RESULT,
        LIMITATIONS,
    )
}

object ScientificInformationAssetFactory {
    fun request(
        namespace: String,
        stableKey: String,
        title: String,
    ): InformationAssetRequest = InformationAssetRequest.create(
        namespace = namespace,
        stableKey = stableKey,
        kind = InformationAssetKind.SCIENTIFIC,
        title = title,
        primaryDomainId = StandardInformationDomains.SCIENCE,
        requiredSemanticKeys = ScientificSemanticKeys.CORE,
    )
}

/**
 * Scientific result claims require science-domain evidence with documented-or-stronger authority.
 * Owner observations remain valid source Photons/evidence, but cannot by themselves be promoted to
 * a supported scientific result without stronger evidence.
 */
class ScientificInformationAssetValidationProfile : InformationAssetValidationProfile {
    override val id: String = "lifeos.scientific.v1"

    override fun validate(
        revision: InformationAssetRevision,
        evaluatedAt: Instant,
    ): List<InformationAssetValidationViolation> {
        if (revision.request.kind != InformationAssetKind.SCIENTIFIC) return emptyList()

        val evidenceById = revision.evidenceBindings.associateBy { it.id }
        return revision.claims
            .filter {
                it.semanticKey == ScientificSemanticKeys.RESULT &&
                    it.state != InformationClaimState.ASSUMPTION &&
                    it.state != InformationClaimState.REJECTED
            }
            .flatMap { claim ->
                val scienceEvidence = claim.evidenceBindingIds
                    .mapNotNull(evidenceById::get)
                    .filter { it.domainId == StandardInformationDomains.SCIENCE }
                buildList {
                    if (scienceEvidence.isEmpty()) {
                        add(requirement(
                            path = "claims[${claim.id.value}].evidence",
                            message = "Scientific result claim requires science-domain evidence",
                        ))
                    } else if (scienceEvidence.none { it.authority in ACCEPTED_RESULT_AUTHORITIES }) {
                        add(requirement(
                            path = "claims[${claim.id.value}].authority",
                            message = "Scientific result requires DOCUMENTED, PRIMARY_SOURCE, OFFICIAL, or AUTHORITATIVE evidence",
                        ))
                    }
                }
            }
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
        val ACCEPTED_RESULT_AUTHORITIES: Set<SourceAuthority> = setOf(
            SourceAuthority.DOCUMENTED,
            SourceAuthority.PRIMARY_SOURCE,
            SourceAuthority.OFFICIAL,
            SourceAuthority.AUTHORITATIVE,
        )
    }
}
