package app.lifeos.core.runtime.sourcegraph.resolver

import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.source.SourceObjectKind
import app.lifeos.core.runtime.sourcegraph.EvidencePolarity
import app.lifeos.core.runtime.sourcegraph.EvidenceStrength
import app.lifeos.core.runtime.sourcegraph.RelationshipEvidenceFamily
import app.lifeos.core.runtime.sourcegraph.RelationshipEvidenceKind
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipEvidence
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipType
import java.time.Duration

class EventRelationshipResolver : DirectedSourceRelationshipResolver {
    override val resolverId: String = "event-relationship"
    override val resolverVersion: String = "m208/v1"

    override fun resolve(
        left: SourceResolutionInput,
        right: SourceResolutionInput,
    ): List<DirectedSourceRelationshipResolution> {
        val results = mutableListOf<DirectedSourceRelationshipResolution>()
        val evidence = mutableListOf<SourceRelationshipEvidence>()
        val sameAccount =
            left.metadata.externalObject.account.fingerprint ==
                right.metadata.externalObject.account.fingerprint

        val leftId = eventIdentity(left)
        val rightId = eventIdentity(right)
        if (sameAccount && leftId != null && leftId == rightId) {
            evidence += positive(
                left.sourceRef,
                RelationshipEvidenceFamily.IDENTITY,
                RelationshipEvidenceKind.EVENT_ID,
                EvidenceStrength.DETERMINISTIC,
                1.0,
                "exact provider-account event id match",
            )
        }

        val leftUid = eventUid(left)
        val rightUid = eventUid(right)
        if (sameAccount && leftUid != null && leftUid == rightUid) {
            evidence += positive(
                right.sourceRef,
                RelationshipEvidenceFamily.STRUCTURAL,
                RelationshipEvidenceKind.CALENDAR_UID,
                EvidenceStrength.DETERMINISTIC,
                1.0,
                "exact provider-account calendar uid match",
            )
        }

        val leftTitle = eventTitle(left)
        val rightTitle = eventTitle(right)
        if (leftTitle != null && leftTitle == rightTitle) {
            evidence += positive(
                right.sourceRef,
                RelationshipEvidenceFamily.SEMANTIC,
                RelationshipEvidenceKind.TITLE,
                EvidenceStrength.SUPPORTING,
                0.65,
                "normalized event title match",
            )
        }

        val leftAt = left.metadata.timestamps.occurredAt ?: left.metadata.timestamps.createdAt
        val rightAt = right.metadata.timestamps.occurredAt ?: right.metadata.timestamps.createdAt
        if (leftAt != null && rightAt != null && Duration.between(leftAt, rightAt).abs() <= MAX_PROXIMITY) {
            evidence += positive(
                right.sourceRef,
                RelationshipEvidenceFamily.TEMPORAL,
                RelationshipEvidenceKind.TIME_PROXIMITY,
                EvidenceStrength.WEAK,
                0.45,
                "events occur within bounded temporal proximity",
            )
        }

        if (evidence.isNotEmpty()) {
            val ordered = listOf(left.sourceRef, right.sourceRef).sortedWith(REF_ORDER)
            results += DirectedSourceRelationshipResolution(
                sourceRef = ordered[0],
                targetRef = ordered[1],
                type = SourceRelationshipType.SAME_EVENT,
                evidence = evidence,
            )
        }

        membership(left, right)?.let(results::add)
        membership(right, left)?.let(results::add)
        return results.distinctBy { Triple(it.sourceRef, it.targetRef, it.type) }
            .sortedWith(
                compareBy<DirectedSourceRelationshipResolution> { it.sourceRef.photonId.value }
                    .thenBy { it.targetRef.photonId.value }
                    .thenBy { it.type.name }
            )
    }

    private fun membership(
        member: SourceResolutionInput,
        event: SourceResolutionInput,
    ): DirectedSourceRelationshipResolution? {
        val target = member.metadata.technical.attributes["event:belongs-to-id"]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val eventId = eventIdentity(event) ?: return null
        if (target != eventId) return null
        if (
            member.metadata.externalObject.provider.providerId !=
                event.metadata.externalObject.provider.providerId
        ) return null
        return DirectedSourceRelationshipResolution(
            sourceRef = member.sourceRef,
            targetRef = event.sourceRef,
            type = SourceRelationshipType.BELONGS_TO_EVENT,
            evidence = listOf(
                positive(
                    member.sourceRef,
                    RelationshipEvidenceFamily.OPERATIONAL,
                    RelationshipEvidenceKind.EVENT_ID,
                    EvidenceStrength.DETERMINISTIC,
                    1.0,
                    "explicit event membership id",
                )
            ),
        )
    }

    private fun eventIdentity(input: SourceResolutionInput): String? =
        input.metadata.technical.attributes["event:id"]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: input.metadata.externalObject.externalId
                .takeIf { input.metadata.objectKind == SourceObjectKind.CALENDAR_EVENT }
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

    private fun eventUid(input: SourceResolutionInput): String? =
        input.metadata.technical.attributes["event:uid"]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: input.metadata.technical.attributes["calendar:uid"]
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

    private fun eventTitle(input: SourceResolutionInput): String? =
        input.metadata.technical.attributes["event:title"]?.canonicalText()
            ?: input.metadata.document?.title?.canonicalText()

    private fun positive(
        sourceRef: PhotonRevisionRef,
        family: RelationshipEvidenceFamily,
        kind: RelationshipEvidenceKind,
        strength: EvidenceStrength,
        confidence: Double,
        explanation: String,
    ): SourceRelationshipEvidence = SourceRelationshipEvidence.create(
        family = family,
        kind = kind,
        strength = strength,
        polarity = EvidencePolarity.POSITIVE,
        sourceRef = sourceRef,
        confidence = confidence,
        explanation = explanation,
    )

    private fun String.canonicalText(): String =
        trim().replace(Regex("\\s+"), " ").lowercase().takeIf { it.isNotEmpty() } ?: ""

    private companion object {
        val MAX_PROXIMITY: Duration = Duration.ofMinutes(10)
        val REF_ORDER = compareBy<PhotonRevisionRef> { it.photonId.value }.thenBy { it.revision }
    }
}
