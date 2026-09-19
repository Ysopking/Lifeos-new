package app.lifeos.core.runtime.source

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.source.CanonicalSourceMetadata
import app.lifeos.core.model.source.SourceAccountRef
import app.lifeos.core.model.source.SourceActorMetadata
import app.lifeos.core.model.source.SourceConversationMetadata
import app.lifeos.core.model.source.SourceExternalObjectRef
import app.lifeos.core.model.source.SourceMetadataOrigin
import app.lifeos.core.model.source.SourceObjectKind
import app.lifeos.core.model.source.SourcePrivacyZone
import app.lifeos.core.model.source.SourceProviderRef
import app.lifeos.core.model.source.SourceTechnicalMetadata
import app.lifeos.core.model.source.SourceTimestamps
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SourceMetadataCodecTest {
    private val source = Photon(
        id = PhotonId("source-1"),
        revision = 7,
        content = "source payload",
        confidence = 0.91,
        provenance = Provenance(
            source = "test",
            actor = "test",
            createdAt = Instant.parse("2026-09-19T10:00:00Z"),
        ),
    )

    @Test
    fun codecRoundTripsCanonicalMetadataDeterministically() {
        val record = SourceMetadataRecord(
            sourceRef = PhotonRevisionRef(source.id, source.revision),
            metadata = metadata(),
        )

        val encoded = SourceMetadataCodec.encode(record)
        val decoded = SourceMetadataCodec.decode(encoded)

        assertEquals(record, decoded)
        assertEquals(encoded, SourceMetadataCodec.encode(decoded))
    }

    @Test
    fun companionIdentityIsBoundToExactSourceRevisionNotRawExternalValues() {
        val first = SourceMetadataPhotonFactory.create(source, metadata())
        val second = SourceMetadataPhotonFactory.create(
            source.copy(revision = 8),
            metadata(),
        )

        assertTrue(first.id != second.id)
        assertEquals(setOf(source.id), first.provenance.parentIds)
        assertTrue("source-metadata" in first.tags)
        assertTrue(first.tags.none { "account-a" in it || "thread-a" in it })
    }

    @Test
    fun codecRejectsTrailingOrMalformedEnvelopeData() {
        val encoded = SourceMetadataCodec.encode(
            SourceMetadataRecord(
                sourceRef = PhotonRevisionRef(source.id, source.revision),
                metadata = metadata(),
            )
        )

        assertFailsWith<IllegalArgumentException> {
            SourceMetadataCodec.decode(encoded + "not-valid")
        }
        assertFailsWith<IllegalArgumentException> {
            SourceMetadataCodec.decode("wrong-envelope")
        }
    }

    private fun metadata(): CanonicalSourceMetadata = CanonicalSourceMetadata(
        objectKind = SourceObjectKind.MESSAGE,
        origin = SourceMetadataOrigin.CONNECTOR,
        privacyZone = SourcePrivacyZone.SENSITIVE,
        externalObject = SourceExternalObjectRef(
            provider = SourceProviderRef("provider-a"),
            account = SourceAccountRef("provider-a", "account-a"),
            objectKind = SourceObjectKind.MESSAGE,
            externalId = "message-a",
            externalVersion = "version-1",
        ),
        timestamps = SourceTimestamps(
            occurredAt = Instant.parse("2026-09-19T09:59:00Z"),
            observedAt = Instant.parse("2026-09-19T10:00:00Z"),
            importedAt = Instant.parse("2026-09-19T10:00:01Z"),
        ),
        actor = SourceActorMetadata(
            actorId = "actor-a",
            displayName = "Actor",
            role = "sender",
        ),
        conversation = SourceConversationMetadata(
            conversationId = "conversation-a",
            threadId = "thread-a",
            participants = setOf(
                SourceActorMetadata(actorId = "actor-a", role = "sender"),
                SourceActorMetadata(actorId = "actor-b", role = "recipient"),
            ),
        ),
        technical = SourceTechnicalMetadata(
            format = "text/plain",
            attributes = mapOf(
                "source:transport" to "test",
                "source:version" to "1",
            ),
        ),
    )
}
