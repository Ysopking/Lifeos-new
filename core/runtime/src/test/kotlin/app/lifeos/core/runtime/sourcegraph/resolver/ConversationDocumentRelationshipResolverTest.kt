package app.lifeos.core.runtime.sourcegraph.resolver

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.source.CanonicalSourceMetadata
import app.lifeos.core.model.source.SourceAccountRef
import app.lifeos.core.model.source.SourceActorMetadata
import app.lifeos.core.model.source.SourceConversationMetadata
import app.lifeos.core.model.source.SourceDocumentMetadata
import app.lifeos.core.model.source.SourceExternalObjectRef
import app.lifeos.core.model.source.SourceFileMetadata
import app.lifeos.core.model.source.SourceMetadataOrigin
import app.lifeos.core.model.source.SourceObjectKind
import app.lifeos.core.model.source.SourcePrivacyZone
import app.lifeos.core.model.source.SourceProviderRef
import app.lifeos.core.model.source.SourceTechnicalMetadata
import app.lifeos.core.model.source.SourceTimestamps
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipPolicy
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipState
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationDocumentRelationshipResolverTest {
    private val at = Instant.parse("2026-09-19T15:00:00Z")
    private val policy = SourceRelationshipPolicy()

    @Test
    fun exactThreadIdConfirmsConversationAndReplyDirectionIsPreserved() {
        val parent = input(
            "parent",
            messageMetadata(
                externalId = "m1",
                conversation = SourceConversationMetadata(
                    conversationId = "c1",
                    threadId = "t1",
                    participants = participants(),
                ),
            ),
        )
        val reply = input(
            "reply",
            messageMetadata(
                externalId = "m2",
                conversation = SourceConversationMetadata(
                    conversationId = "c1",
                    threadId = "t1",
                    replyTo = "m1",
                    participants = participants(),
                ),
            ),
        )

        val resolutions = ConversationRelationshipResolver().resolve(parent, reply)
        val sameConversation = resolutions.single {
            it.type == SourceRelationshipType.SAME_CONVERSATION
        }
        val replyTo = resolutions.single { it.type == SourceRelationshipType.REPLY_TO }

        assertEquals(
            SourceRelationshipState.CONFIRMED,
            policy.evaluate(sameConversation.type, sameConversation.evidence).state,
        )
        assertEquals(reply.sourceRef, replyTo.sourceRef)
        assertEquals(parent.sourceRef, replyTo.targetRef)
        assertEquals(
            SourceRelationshipState.CONFIRMED,
            policy.evaluate(replyTo.type, replyTo.evidence).state,
        )
    }

    @Test
    fun participantsAndTimeWithoutThreadIdStayCandidate() {
        val left = input(
            "left",
            messageMetadata(
                externalId = "a",
                occurredAt = at,
                conversation = SourceConversationMetadata(participants = participants()),
            ),
        )
        val right = input(
            "right",
            messageMetadata(
                externalId = "b",
                occurredAt = at.plusSeconds(300),
                conversation = SourceConversationMetadata(participants = participants()),
            ),
        )

        val sameConversation = ConversationRelationshipResolver()
            .resolve(left, right)
            .single { it.type == SourceRelationshipType.SAME_CONVERSATION }

        assertEquals(
            SourceRelationshipState.CANDIDATE,
            policy.evaluate(sameConversation.type, sameConversation.evidence).state,
        )
    }

    @Test
    fun sameFilenameAloneCannotCreateAutomaticDocumentMerge() {
        val left = input("left", documentMetadata("a", null, "report.pdf"))
        val right = input("right", documentMetadata("b", null, "report.pdf"))

        val sameDocument = DocumentRelationshipResolver()
            .resolve(left, right)
            .single { it.type == SourceRelationshipType.SAME_LOGICAL_DOCUMENT }

        assertEquals(
            SourceRelationshipState.CANDIDATE,
            policy.evaluate(sameDocument.type, sameDocument.evidence).state,
        )
    }

    @Test
    fun explicitLogicalIdAndVersionSequenceProduceConfirmedDocumentRelations() {
        val older = input(
            "older",
            documentMetadata(
                externalId = "doc-a",
                logicalId = "logical-42",
                filename = "report.pdf",
                sequence = "1",
            ),
        )
        val newer = input(
            "newer",
            documentMetadata(
                externalId = "doc-b",
                logicalId = "logical-42",
                filename = "report-v2.pdf",
                sequence = "2",
            ),
        )

        val resolutions = DocumentRelationshipResolver().resolve(older, newer)
        val sameDocument = resolutions.single {
            it.type == SourceRelationshipType.SAME_LOGICAL_DOCUMENT
        }
        val revision = resolutions.single { it.type == SourceRelationshipType.REVISION_OF }

        assertEquals(
            SourceRelationshipState.CONFIRMED,
            policy.evaluate(sameDocument.type, sameDocument.evidence).state,
        )
        assertEquals(newer.sourceRef, revision.sourceRef)
        assertEquals(older.sourceRef, revision.targetRef)
        assertEquals(
            SourceRelationshipState.CONFIRMED,
            policy.evaluate(revision.type, revision.evidence).state,
        )
    }

    private fun input(id: String, metadata: CanonicalSourceMetadata): SourceResolutionInput =
        SourceResolutionInput(
            sourceRef = PhotonRevisionRef(PhotonId(id), 1),
            metadata = metadata,
        )

    private fun messageMetadata(
        externalId: String,
        occurredAt: Instant = at,
        conversation: SourceConversationMetadata,
    ): CanonicalSourceMetadata = CanonicalSourceMetadata(
        objectKind = SourceObjectKind.MESSAGE,
        origin = SourceMetadataOrigin.CONNECTOR,
        privacyZone = SourcePrivacyZone.PRIVATE,
        externalObject = SourceExternalObjectRef(
            provider = SourceProviderRef("mail"),
            account = SourceAccountRef("mail", "account"),
            objectKind = SourceObjectKind.MESSAGE,
            externalId = externalId,
            externalVersion = "v1",
        ),
        timestamps = SourceTimestamps(
            occurredAt = occurredAt,
            observedAt = occurredAt,
        ),
        conversation = conversation,
    )

    private fun documentMetadata(
        externalId: String,
        logicalId: String?,
        filename: String,
        sequence: String? = null,
    ): CanonicalSourceMetadata = CanonicalSourceMetadata(
        objectKind = SourceObjectKind.DOCUMENT,
        origin = SourceMetadataOrigin.CONNECTOR,
        privacyZone = SourcePrivacyZone.PRIVATE,
        externalObject = SourceExternalObjectRef(
            provider = SourceProviderRef("files"),
            account = SourceAccountRef("files", "account"),
            objectKind = SourceObjectKind.DOCUMENT,
            externalId = externalId,
            externalVersion = sequence ?: "v1",
        ),
        document = SourceDocumentMetadata(
            logicalDocumentId = logicalId,
            title = filename,
        ),
        file = SourceFileMetadata(
            name = filename,
            mimeType = "application/pdf",
        ),
        technical = SourceTechnicalMetadata(
            attributes = sequence?.let {
                mapOf("document:version-sequence" to it)
            }.orEmpty(),
        ),
    )

    private fun participants(): Set<SourceActorMetadata> = setOf(
        SourceActorMetadata(actorId = "alice", displayName = "Alice"),
        SourceActorMetadata(actorId = "bob", displayName = "Bob"),
    )
}
