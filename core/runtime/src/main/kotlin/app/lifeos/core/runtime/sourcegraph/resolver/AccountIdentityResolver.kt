package app.lifeos.core.runtime.sourcegraph.resolver

import app.lifeos.core.runtime.sourcegraph.EvidencePolarity
import app.lifeos.core.runtime.sourcegraph.EvidenceStrength
import app.lifeos.core.runtime.sourcegraph.RelationshipEvidenceFamily
import app.lifeos.core.runtime.sourcegraph.RelationshipEvidenceKind
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipEvidence
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipType

class AccountIdentityResolver : SourceRelationshipResolver {
    override val resolverId: String = "account-identity"
    override val resolverVersion: String = "m206/v1"

    override fun resolve(
        left: SourceResolutionInput,
        right: SourceResolutionInput,
    ): List<SourceRelationshipResolution> {
        val leftAccount = left.metadata.externalObject.account
        val rightAccount = right.metadata.externalObject.account
        if (leftAccount.fingerprint != rightAccount.fingerprint) return emptyList()

        return listOf(
            SourceRelationshipResolution(
                type = SourceRelationshipType.SAME_ACCOUNT,
                evidence = listOf(
                    SourceRelationshipEvidence.create(
                        family = RelationshipEvidenceFamily.IDENTITY,
                        kind = RelationshipEvidenceKind.ACCOUNT_ID,
                        strength = EvidenceStrength.DETERMINISTIC,
                        polarity = EvidencePolarity.POSITIVE,
                        sourceRef = left.sourceRef,
                        confidence = 1.0,
                        explanation = "exact provider/account identity match",
                    )
                ),
            )
        )
    }
}
