package app.lifeos.core.runtime.sourcegraph.resolver

import app.lifeos.core.runtime.sourcegraph.EvidencePolarity
import app.lifeos.core.runtime.sourcegraph.EvidenceStrength
import app.lifeos.core.runtime.sourcegraph.RelationshipEvidenceFamily
import app.lifeos.core.runtime.sourcegraph.RelationshipEvidenceKind
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipEvidence
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipType

class OrganizationIdentityResolver : SourceRelationshipResolver {
    override val resolverId: String = "organization-identity"
    override val resolverVersion: String = "m206/v1"

    override fun resolve(
        left: SourceResolutionInput,
        right: SourceResolutionInput,
    ): List<SourceRelationshipResolution> {
        val leftId = left.metadata.technical.attributes["entity:organization-id"]
        val rightId = right.metadata.technical.attributes["entity:organization-id"]
        val leftName = left.metadata.technical.attributes["entity:organization-name"]
        val rightName = right.metadata.technical.attributes["entity:organization-name"]

        val evidence = buildList {
            if (leftId != null && leftId == rightId) {
                add(
                    SourceRelationshipEvidence.create(
                        family = RelationshipEvidenceFamily.IDENTITY,
                        kind = RelationshipEvidenceKind.ORGANIZATION_ID,
                        strength = EvidenceStrength.DETERMINISTIC,
                        polarity = EvidencePolarity.POSITIVE,
                        sourceRef = left.sourceRef,
                        confidence = 1.0,
                        explanation = "exact explicit organization id match",
                    )
                )
            }
            if (
                leftName != null &&
                rightName != null &&
                leftName.trim().equals(rightName.trim(), ignoreCase = true)
            ) {
                add(
                    SourceRelationshipEvidence.create(
                        family = RelationshipEvidenceFamily.ENTITY,
                        kind = RelationshipEvidenceKind.ENTITY_MATCH,
                        strength = EvidenceStrength.SUPPORTING,
                        polarity = EvidencePolarity.POSITIVE,
                        sourceRef = right.sourceRef,
                        confidence = 0.7,
                        explanation = "organization name match is supporting evidence only",
                    )
                )
            }
        }

        if (evidence.isEmpty()) return emptyList()
        return listOf(
            SourceRelationshipResolution(
                type = SourceRelationshipType.SAME_ORGANIZATION,
                evidence = evidence,
            )
        )
    }
}
