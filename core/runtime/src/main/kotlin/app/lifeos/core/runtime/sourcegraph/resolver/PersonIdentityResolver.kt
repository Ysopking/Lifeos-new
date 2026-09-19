package app.lifeos.core.runtime.sourcegraph.resolver

import app.lifeos.core.runtime.sourcegraph.EvidencePolarity
import app.lifeos.core.runtime.sourcegraph.EvidenceStrength
import app.lifeos.core.runtime.sourcegraph.RelationshipEvidenceFamily
import app.lifeos.core.runtime.sourcegraph.RelationshipEvidenceKind
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipEvidence
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipType

class PersonIdentityResolver : SourceRelationshipResolver {
    override val resolverId: String = "person-identity"
    override val resolverVersion: String = "m206/v1"

    override fun resolve(
        left: SourceResolutionInput,
        right: SourceResolutionInput,
    ): List<SourceRelationshipResolution> {
        val leftActor = left.metadata.actor ?: return emptyList()
        val rightActor = right.metadata.actor ?: return emptyList()
        val evidence = mutableListOf<SourceRelationshipEvidence>()

        val sameAccount =
            left.metadata.externalObject.account.fingerprint ==
                right.metadata.externalObject.account.fingerprint

        val leftActorId = leftActor.actorId?.trim()?.takeIf { it.isNotEmpty() }
        val rightActorId = rightActor.actorId?.trim()?.takeIf { it.isNotEmpty() }
        if (sameAccount && leftActorId != null && rightActorId != null) {
            if (leftActorId == rightActorId) {
                evidence += SourceRelationshipEvidence.create(
                    family = RelationshipEvidenceFamily.IDENTITY,
                    kind = RelationshipEvidenceKind.ACTOR_ID,
                    strength = EvidenceStrength.DETERMINISTIC,
                    polarity = EvidencePolarity.POSITIVE,
                    sourceRef = left.sourceRef,
                    confidence = 1.0,
                    explanation = "exact actor id within the same provider account",
                )
            } else {
                evidence += SourceRelationshipEvidence.create(
                    family = RelationshipEvidenceFamily.IDENTITY,
                    kind = RelationshipEvidenceKind.ACTOR_ID,
                    strength = EvidenceStrength.DETERMINISTIC,
                    polarity = EvidencePolarity.NEGATIVE,
                    sourceRef = left.sourceRef,
                    confidence = 1.0,
                    explanation = "different explicit actor ids within the same provider account",
                )
            }
        }

        val leftAddress = canonicalAddress(leftActor.address)
        val rightAddress = canonicalAddress(rightActor.address)
        if (leftAddress != null && leftAddress == rightAddress) {
            val kind = if ('@' in leftAddress) {
                RelationshipEvidenceKind.EMAIL
            } else {
                RelationshipEvidenceKind.PHONE
            }
            evidence += SourceRelationshipEvidence.create(
                family = RelationshipEvidenceFamily.SOCIAL,
                kind = kind,
                strength = EvidenceStrength.STRONG,
                polarity = EvidencePolarity.POSITIVE,
                sourceRef = right.sourceRef,
                confidence = 0.95,
                explanation = "canonical contact address match",
            )
        }

        val leftName = canonicalName(leftActor.displayName)
        val rightName = canonicalName(rightActor.displayName)
        if (leftName != null && leftName == rightName) {
            evidence += SourceRelationshipEvidence.create(
                family = RelationshipEvidenceFamily.ENTITY,
                kind = RelationshipEvidenceKind.ENTITY_MATCH,
                strength = EvidenceStrength.SUPPORTING,
                polarity = EvidencePolarity.POSITIVE,
                sourceRef = right.sourceRef,
                confidence = 0.65,
                explanation = "display-name match is supporting evidence only",
            )
        }

        if (evidence.isEmpty()) return emptyList()
        return listOf(
            SourceRelationshipResolution(
                type = SourceRelationshipType.SAME_PERSON,
                evidence = evidence,
            )
        )
    }

    private fun canonicalAddress(value: String?): String? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if ('@' in raw) {
            raw.lowercase()
        } else {
            raw.filter { it.isDigit() || it == '+' }.takeIf { it.isNotEmpty() }
        }
    }

    private fun canonicalName(value: String?): String? =
        value
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
}
