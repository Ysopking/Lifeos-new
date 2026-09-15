package app.lifeos.core.runtime.informationasset.organization

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

enum class OrganizationActorType {
    PERSON,
    TEAM,
    ROLE,
    UNIT,
    SYSTEM,
    EXTERNAL_PARTY,
}

data class OrganizationActor(
    val id: String,
    val name: String,
    val type: OrganizationActorType,
) {
    init {
        require(id.isNotBlank()) { "Organization actor id must not be blank" }
        require(name.isNotBlank()) { "Organization actor name must not be blank" }
    }
}

data class OrganizationConstraint(
    val key: String,
    val statement: String,
    val mandatory: Boolean,
) {
    init {
        require(key.isNotBlank()) { "Organization constraint key must not be blank" }
        require(statement.isNotBlank()) { "Organization constraint statement must not be blank" }
    }
}

data class OrganizationDecision(
    val id: String,
    val statement: String,
    val decidedBy: Set<OrganizationActor>,
    val decidedAt: Instant?,
) {
    init {
        require(id.isNotBlank()) { "Organization decision id must not be blank" }
        require(statement.isNotBlank()) { "Organization decision statement must not be blank" }
        require(decidedBy.none { it.id.isBlank() }) { "Organization decision actors must have ids" }
    }
}

object OrganizationSemanticKeys {
    const val CONTEXT = "organization.context"
    const val OBJECTIVE = "organization.objective"
    const val STAKEHOLDERS = "organization.stakeholders"
    const val CONSTRAINTS = "organization.constraints"
    const val DECISION = "organization.decision"
    const val ACTION = "organization.action"

    val CORE: Set<String> = linkedSetOf(
        CONTEXT,
        OBJECTIVE,
        STAKEHOLDERS,
        CONSTRAINTS,
        DECISION,
        ACTION,
    )
}

object OrganizationInformationAssetFactory {
    fun request(
        namespace: String,
        stableKey: String,
        title: String,
    ): InformationAssetRequest = InformationAssetRequest.create(
        namespace = namespace,
        stableKey = stableKey,
        kind = InformationAssetKind.ORGANIZATION,
        title = title,
        primaryDomainId = StandardInformationDomains.ORGANIZATION,
        requiredSemanticKeys = OrganizationSemanticKeys.CORE,
    )
}

/**
 * Organizational decisions must remain grounded in organization-domain evidence. User-provided
 * authority is explicitly accepted because owner-entered decisions are legitimate organizational
 * truth; completely unverified evidence is not silently promoted to a decision.
 */
class OrganizationInformationAssetValidationProfile : InformationAssetValidationProfile {
    override val id: String = "lifeos.organization.v1"

    override fun validate(
        revision: InformationAssetRevision,
        evaluatedAt: Instant,
    ): List<InformationAssetValidationViolation> {
        if (revision.request.kind != InformationAssetKind.ORGANIZATION) return emptyList()

        val evidenceById = revision.evidenceBindings.associateBy { it.id }
        return revision.claims
            .asSequence()
            .filter {
                it.semanticKey == OrganizationSemanticKeys.DECISION &&
                    it.state != InformationClaimState.ASSUMPTION &&
                    it.state != InformationClaimState.REJECTED
            }
            .flatMap { claim ->
                val supporting = claim.evidenceBindingIds.mapNotNull(evidenceById::get)
                val organizationEvidence = supporting.filter {
                    it.domainId == StandardInformationDomains.ORGANIZATION
                }
                buildList {
                    if (organizationEvidence.isEmpty()) {
                        add(requirement(
                            path = "claims[${claim.id.value}].evidence",
                            message = "Organization decision claim requires organization-domain evidence",
                        ))
                    } else if (organizationEvidence.none { it.authority in ACCEPTED_DECISION_AUTHORITIES }) {
                        add(requirement(
                            path = "claims[${claim.id.value}].authority",
                            message = "Organization decision claim cannot rely only on UNVERIFIED evidence",
                        ))
                    }
                }.asSequence()
            }
            .toList()
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
        val ACCEPTED_DECISION_AUTHORITIES: Set<SourceAuthority> = setOf(
            SourceAuthority.USER_PROVIDED,
            SourceAuthority.DOCUMENTED,
            SourceAuthority.PRIMARY_SOURCE,
            SourceAuthority.OFFICIAL,
            SourceAuthority.AUTHORITATIVE,
        )
    }
}
