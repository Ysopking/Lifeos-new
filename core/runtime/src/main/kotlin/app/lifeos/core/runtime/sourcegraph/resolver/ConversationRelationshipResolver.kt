package app.lifeos.core.runtime.sourcegraph.resolver

import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.source.SourceActorMetadata
import app.lifeos.core.runtime.sourcegraph.EvidencePolarity
import app.lifeos.core.runtime.sourcegraph.EvidenceStrength
import app.lifeos.core.runtime.sourcegraph.RelationshipEvidenceFamily
import app.lifeos.core.runtime.sourcegraph.RelationshipEvidenceKind
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipEvidence
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipType
import java.time.Duration

class ConversationRelationshipResolver : DirectedSourceRelationshipResolver {
    override val resolverId: String = "conversation-relationship"
    override val resolverVersion: String = "m207/v1"

    override fun resolve(
        left: SourceResolutionInput,
        right: SourceResolutionInput,
    ): List<DirectedSourceRelationshipResolution> {
        val results = mutableListOf<DirectedSourceRelationshipResolution>()
        val leftConversation = left.metadata.conversation
        val rightConversation = right.metadata.conversation

        val sameConversationEvidence = mutableListOf<SourceRelationshipEvidence>()
        val sameAccount =
            left.metadata.externalObject.account.fingerprint ==
                right.metadata.externalObject.account.fingerprint

        if (sameAccount && leftConversation != null && rightConversation != null) {
            val leftConversationId = leftConversation.conversationId?.takeIf { it.isNotBlank() }
            val rightConversationId = rightConversation.conversationId?.takeIf { it.isNotBlank() }
            if (leftConversationId != null && leftConversationId == rightConversationId) {
                sameConversationEvidence += evidence(
                    sourceRef = left.sourceRef,
                    family = RelationshipEvidenceFamily.IDENTITY,
                    kind = RelationshipEvidenceKind.THREAD_ID,
                    strength = EvidenceStrength.DETERMINISTIC,
                    confidence = 1.0,
                    explanation = "exact provider-account conversation id match",
                )
            }

            val leftThreadId = leftConversation.threadId?.takeIf { it.isNotBlank() }
            val rightThreadId = rightConversation.threadId?.takeIf { it.isNotBlank() }
            if (leftThreadId != null && leftThreadId == rightThreadId) {
                sameConversationEvidence += evidence(
                    sourceRef = right.sourceRef,
                    family = RelationshipEvidenceFamily.STRUCTURAL,
                    kind = RelationshipEvidenceKind.THREAD_ID,
                    strength = EvidenceStrength.DETERMINISTIC,
                    confidence = 1.0,
                    explanation = "exact provider-account thread id match",
                )
            }
        }

        val participantOverlap = participantOverlap(leftConversation?.participants, rightConversation?.participants)
        if (participantOverlap > 0) {
            sameConversationEvidence += evidence(
                sourceRef = right.sourceRef,
                family = RelationshipEvidenceFamily.SOCIAL,
                kind = RelationshipEvidenceKind.PARTICIPANT_OVERLAP,
                strength = EvidenceStrength.SUPPORTING,
                confidence = participantOverlap.coerceIn(0.0, 1.0),
                explanation = "conversation participant overlap",
            )
        }

        val leftSubject = left.metadata.document?.subject?.canonicalText()
        val rightSubject = right.metadata.document?.subject?.canonicalText()
        if (leftSubject != null && leftSubject == rightSubject) {
            sameConversationEvidence += evidence(
                sourceRef = right.sourceRef,
                family = RelationshipEvidenceFamily.CONTEXT,
                kind = RelationshipEvidenceKind.SUBJECT,
                strength = EvidenceStrength.SUPPORTING,
                confidence = 0.7,
                explanation = "normalized conversation subject match",
            )
        }

        val leftAt = left.metadata.timestamps.occurredAt ?: left.metadata.timestamps.sentAt
        val rightAt = right.metadata.timestamps.occurredAt ?: right.metadata.timestamps.sentAt
        if (leftAt != null && rightAt != null) {
            val distance = Duration.between(leftAt, rightAt).abs()
            if (distance <= MAX_PROXIMITY) {
                sameConversationEvidence += evidence(
                    sourceRef = right.sourceRef,
                    family = RelationshipEvidenceFamily.TEMPORAL,
                    kind = RelationshipEvidenceKind.TIME_PROXIMITY,
                    strength = EvidenceStrength.WEAK,
                    confidence = 0.45,
                    explanation = "messages occurred within bounded conversation proximity",
                )
            }
        }

        if (sameConversationEvidence.isNotEmpty()) {
            results += DirectedSourceRelationshipResolution(
                sourceRef = canonicalFirst(left.sourceRef, right.sourceRef),
                targetRef = canonicalSecond(left.sourceRef, right.sourceRef),
                type = SourceRelationshipType.SAME_CONVERSATION,
                evidence = sameConversationEvidence,
            )
        }

        leftConversation?.let { conversation ->
            directional(
                owner = left,
                other = right,
                rawTargetId = conversation.replyTo ?: conversation.parentMessageId,
                type = SourceRelationshipType.REPLY_TO,
                kind = RelationshipEvidenceKind.REPLY_ID,
                explanation = "explicit reply/parent message id",
            )?.let(results::add)
            directional(
                owner = left,
                other = right,
                rawTargetId = conversation.quotedId,
                type = SourceRelationshipType.QUOTES,
                kind = RelationshipEvidenceKind.QUOTE_LINEAGE,
                explanation = "explicit quoted message id",
            )?.let(results::add)
            directional(
                owner = left,
                other = right,
                rawTargetId = conversation.forwardedFrom,
                type = SourceRelationshipType.FORWARDS,
                kind = RelationshipEvidenceKind.FORWARD_LINEAGE,
                explanation = "explicit forwarded-from message id",
            )?.let(results::add)
        }
        rightConversation?.let { conversation ->
            directional(
                owner = right,
                other = left,
                rawTargetId = conversation.replyTo ?: conversation.parentMessageId,
                type = SourceRelationshipType.REPLY_TO,
                kind = RelationshipEvidenceKind.REPLY_ID,
                explanation = "explicit reply/parent message id",
            )?.let(results::add)
            directional(
                owner = right,
                other = left,
                rawTargetId = conversation.quotedId,
                type = SourceRelationshipType.QUOTES,
                kind = RelationshipEvidenceKind.QUOTE_LINEAGE,
                explanation = "explicit quoted message id",
            )?.let(results::add)
            directional(
                owner = right,
                other = left,
                rawTargetId = conversation.forwardedFrom,
                type = SourceRelationshipType.FORWARDS,
                kind = RelationshipEvidenceKind.FORWARD_LINEAGE,
                explanation = "explicit forwarded-from message id",
            )?.let(results::add)
        }

        return results
            .distinctBy { listOf(it.sourceRef, it.targetRef, it.type, it.evidence.map { e -> e.evidenceId }) }
            .sortedWith(
                compareBy<DirectedSourceRelationshipResolution> { it.sourceRef.photonId.value }
                    .thenBy { it.targetRef.photonId.value }
                    .thenBy { it.type.name }
            )
    }

    private fun directional(
        owner: SourceResolutionInput,
        other: SourceResolutionInput,
        rawTargetId: String?,
        type: SourceRelationshipType,
        kind: RelationshipEvidenceKind,
        explanation: String,
    ): DirectedSourceRelationshipResolution? {
        val targetId = rawTargetId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (targetId != other.metadata.externalObject.externalId) return null
        if (
            owner.metadata.externalObject.provider.providerId !=
                other.metadata.externalObject.provider.providerId
        ) {
            return null
        }
        return DirectedSourceRelationshipResolution(
            sourceRef = owner.sourceRef,
            targetRef = other.sourceRef,
            type = type,
            evidence = listOf(
                evidence(
                    sourceRef = owner.sourceRef,
                    family = RelationshipEvidenceFamily.STRUCTURAL,
                    kind = kind,
                    strength = EvidenceStrength.DETERMINISTIC,
                    confidence = 1.0,
                    explanation = explanation,
                )
            ),
        )
    }

    private fun evidence(
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

    private fun participantOverlap(
        left: Set<SourceActorMetadata>?,
        right: Set<SourceActorMetadata>?,
    ): Double {
        if (left.isNullOrEmpty() || right.isNullOrEmpty()) return 0.0
        val leftIds = left.mapNotNull(::participantKey).toSet()
        val rightIds = right.mapNotNull(::participantKey).toSet()
        if (leftIds.isEmpty() || rightIds.isEmpty()) return 0.0
        val union = leftIds union rightIds
        if (union.isEmpty()) return 0.0
        return (leftIds intersect rightIds).size.toDouble() / union.size.toDouble()
    }

    private fun participantKey(actor: SourceActorMetadata): String? =
        actor.actorId?.trim()?.takeIf { it.isNotEmpty() }?.let { "id:$it" }
            ?: actor.address?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { "address:$it" }

    private fun String.canonicalText(): String =
        trim().replace(Regex("\\s+"), " ").lowercase()

    private fun canonicalFirst(a: PhotonRevisionRef, b: PhotonRevisionRef): PhotonRevisionRef =
        listOf(a, b).sortedWith(REF_ORDER).first()

    private fun canonicalSecond(a: PhotonRevisionRef, b: PhotonRevisionRef): PhotonRevisionRef =
        listOf(a, b).sortedWith(REF_ORDER).last()

    private companion object {
        val MAX_PROXIMITY: Duration = Duration.ofHours(6)
        val REF_ORDER = compareBy<PhotonRevisionRef> { it.photonId.value }.thenBy { it.revision }
    }
}
