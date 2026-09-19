package app.lifeos.core.runtime.sourcegraph.resolver

import app.lifeos.core.runtime.sourcegraph.EvidencePolarity
import app.lifeos.core.runtime.sourcegraph.EvidenceStrength
import app.lifeos.core.runtime.sourcegraph.RelationshipEvidenceFamily
import app.lifeos.core.runtime.sourcegraph.RelationshipEvidenceKind
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipEvidence
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipType

class DecisionRelationshipResolver : DirectedSourceRelationshipResolver {
    override val resolverId: String = "decision-relationship"
    override val resolverVersion: String = "m208/v1"

    override fun resolve(
        left: SourceResolutionInput,
        right: SourceResolutionInput,
    ): List<DirectedSourceRelationshipResolution> = buildList {
        addAll(resolveDirection(left, right))
        addAll(resolveDirection(right, left))
    }.distinctBy { Triple(it.sourceRef, it.targetRef, it.type) }
        .sortedWith(
            compareBy<DirectedSourceRelationshipResolution> { it.sourceRef.photonId.value }
                .thenBy { it.targetRef.photonId.value }
                .thenBy { it.type.name }
        )

    private fun resolveDirection(
        owner: SourceResolutionInput,
        other: SourceResolutionInput,
    ): List<DirectedSourceRelationshipResolution> {
        val targetId = decisionIdentity(other) ?: return emptyList()
        val mappings = listOf(
            "decision:proposes-id" to SourceRelationshipType.PROPOSES,
            "decision:decides-id" to SourceRelationshipType.DECIDES,
            "decision:approves-id" to SourceRelationshipType.APPROVES,
            "decision:rejects-id" to SourceRelationshipType.REJECTS,
            "decision:revises-id" to SourceRelationshipType.REVISES_DECISION,
            "decision:implements-id" to SourceRelationshipType.IMPLEMENTS_DECISION,
        )
        return mappings.mapNotNull { (key, type) ->
            val explicit = owner.metadata.technical.attributes[key]?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            if (explicit != targetId) return@mapNotNull null
            if (
                owner.metadata.externalObject.provider.providerId !=
                    other.metadata.externalObject.provider.providerId
            ) return@mapNotNull null
            DirectedSourceRelationshipResolution(
                sourceRef = owner.sourceRef,
                targetRef = other.sourceRef,
                type = type,
                evidence = listOf(
                    SourceRelationshipEvidence.create(
                        family = RelationshipEvidenceFamily.OPERATIONAL,
                        kind = RelationshipEvidenceKind.DECISION_ID,
                        strength = EvidenceStrength.DETERMINISTIC,
                        polarity = EvidencePolarity.POSITIVE,
                        sourceRef = owner.sourceRef,
                        confidence = 1.0,
                        explanation = "explicit ${type.name.lowercase()} decision target id",
                    )
                ),
            )
        }
    }

    private fun decisionIdentity(input: SourceResolutionInput): String? =
        input.metadata.technical.attributes["decision:id"]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
}
