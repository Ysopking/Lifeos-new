package app.lifeos.core.model.source

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class CanonicalSourceMetadataTest {
    private val provider = SourceProviderRef("openai", "OpenAI")

    @Test
    fun externalObjectIdentityIsBoundToProviderAndAccount() {
        val first = SourceExternalObjectRef(
            provider = provider,
            account = SourceAccountRef("openai", "account-a"),
            objectKind = SourceObjectKind.CONVERSATION,
            externalId = "conversation-7",
            externalVersion = "3",
        )
        val second = first.copy(
            account = SourceAccountRef("openai", "account-b"),
        )

        assertNotEquals(first.objectFingerprint, second.objectFingerprint)
        assertNotEquals(first.versionFingerprint, second.versionFingerprint)
    }

    @Test
    fun providerAccountMismatchFailsClosed() {
        assertFailsWith<IllegalArgumentException> {
            SourceExternalObjectRef(
                provider = SourceProviderRef("openai"),
                account = SourceAccountRef("google", "account"),
                objectKind = SourceObjectKind.MESSAGE,
                externalId = "message-1",
                externalVersion = "1",
            )
        }
    }

    @Test
    fun metadataFingerprintIsCanonicalAcrossSetAndMapOrdering() {
        val actorA = SourceActorMetadata(
            actorId = "a",
            displayName = "Alice",
            role = "sender",
        )
        val actorB = SourceActorMetadata(
            actorId = "b",
            displayName = "Bob",
            role = "recipient",
        )
        val first = metadata(
            participants = linkedSetOf(actorA, actorB),
            attributes = linkedMapOf(
                "mail:header-b" to "2",
                "mail:header-a" to "1",
            ),
        )
        val second = metadata(
            participants = linkedSetOf(actorB, actorA),
            attributes = linkedMapOf(
                "mail:header-a" to "1",
                "mail:header-b" to "2",
            ),
        )

        assertEquals(first.metadataFingerprint, second.metadataFingerprint)
        assertEquals(first.observationFingerprint, second.observationFingerprint)
    }

    @Test
    fun localImportTimeDoesNotChangeStableMetadataIdentity() {
        val first = metadata(
            observedAt = Instant.parse("2026-09-19T10:00:00Z"),
            importedAt = Instant.parse("2026-09-19T10:00:05Z"),
        )
        val retried = metadata(
            observedAt = Instant.parse("2026-09-19T10:00:10Z"),
            importedAt = Instant.parse("2026-09-19T10:00:15Z"),
        )

        assertEquals(first.metadataFingerprint, retried.metadataFingerprint)
        assertNotEquals(first.observationFingerprint, retried.observationFingerprint)
    }

    @Test
    fun privacyAggregationUsesExplicitRestrictiveness() {
        assertEquals(
            SourcePrivacyZone.SENSITIVE,
            SourcePrivacyZone.mostRestrictive(
                listOf(
                    SourcePrivacyZone.SHAREABLE,
                    SourcePrivacyZone.EPHEMERAL,
                    SourcePrivacyZone.PRIVATE,
                    SourcePrivacyZone.SENSITIVE,
                )
            ),
        )
        assertEquals(
            SourcePrivacyZone.PRIVATE,
            SourcePrivacyZone.mostRestrictive(emptyList()),
        )
    }

    @Test
    fun extensionAttributesAreBoundedAndNamespaced() {
        assertFailsWith<IllegalArgumentException> {
            SourceTechnicalMetadata(
                attributes = mapOf("not-namespaced" to "value"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SourceTechnicalMetadata(
                attributes = (0..128).associate { index ->
                    "test:key-$index" to "value"
                },
            )
        }
    }

    @Test
    fun fileMetadataRequiresExactSha256ShapeWhenPresent() {
        assertFailsWith<IllegalArgumentException> {
            SourceFileMetadata(
                name = "artifact.bin",
                binarySha256 = "ABC",
            )
        }
    }

    private fun metadata(
        participants: Set<SourceActorMetadata> = emptySet(),
        attributes: Map<String, String> = emptyMap(),
        observedAt: Instant = Instant.parse("2026-09-19T10:00:00Z"),
        importedAt: Instant = Instant.parse("2026-09-19T10:00:05Z"),
    ): CanonicalSourceMetadata = CanonicalSourceMetadata(
        objectKind = SourceObjectKind.EMAIL,
        origin = SourceMetadataOrigin.CONNECTOR,
        privacyZone = SourcePrivacyZone.SENSITIVE,
        externalObject = SourceExternalObjectRef(
            provider = provider,
            account = SourceAccountRef("openai", "account-a"),
            objectKind = SourceObjectKind.EMAIL,
            externalId = "message-42",
            externalVersion = "version-3",
        ),
        timestamps = SourceTimestamps(
            sentAt = Instant.parse("2026-09-19T09:00:00Z"),
            receivedAt = Instant.parse("2026-09-19T09:00:01Z"),
            observedAt = observedAt,
            importedAt = importedAt,
        ),
        actor = SourceActorMetadata(
            actorId = "sender-1",
            displayName = "Sender",
            address = "sender@example.test",
            role = "sender",
        ),
        conversation = SourceConversationMetadata(
            conversationId = "conversation-7",
            threadId = "thread-7",
            participants = participants,
        ),
        technical = SourceTechnicalMetadata(
            format = "message/rfc822",
            schema = "mail/v1",
            producer = "test",
            attributes = attributes,
        ),
    )
}
