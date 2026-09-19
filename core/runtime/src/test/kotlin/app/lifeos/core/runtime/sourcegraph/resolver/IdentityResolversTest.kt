package app.lifeos.core.runtime.sourcegraph.resolver

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.source.CanonicalSourceMetadata
import app.lifeos.core.model.source.SourceAccountRef
import app.lifeos.core.model.source.SourceActorMetadata
import app.lifeos.core.model.source.SourceExternalObjectRef
import app.lifeos.core.model.source.SourceMetadataOrigin
import app.lifeos.core.model.source.SourceObjectKind
import app.lifeos.core.model.source.SourcePrivacyZone
import app.lifeos.core.model.source.SourceProviderRef
import app.lifeos.core.model.source.SourceTechnicalMetadata
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipPolicy
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipState
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdentityResolversTest {
    private val policy = SourceRelationshipPolicy()

    @Test
    fun exactProviderAccountAndExternalIdConfirmsSameObject() {
        val left = input("left", metadata(provider = "mail", account = "a", externalId = "m-1"))
        val right = input(
            "right",
            metadata(provider = "mail", account = "a", externalId = "m-1", version = "v2"),
        )

        val resolution = ExactSourceIdentityResolver()
            .resolve(left, right)
            .single { it.type == SourceRelationshipType.SAME_OBJECT }
        val decision = policy.evaluate(resolution.type, resolution.evidence)

        assertEquals(SourceRelationshipState.CONFIRMED, decision.state)
    }

    @Test
    fun identicalDisplayNameAloneNeverMergesPeople() {
        val left = input(
            "left",
            metadata(
                provider = "contacts",
                account = "device",
                externalId = "row-1",
                actor = SourceActorMetadata(displayName = "Thomas Müller"),
            ),
        )
        val right = input(
            "right",
            metadata(
                provider = "contacts",
                account = "device",
                externalId = "row-2",
                actor = SourceActorMetadata(displayName = "Thomas Müller"),
            ),
        )

        val resolution = PersonIdentityResolver().resolve(left, right).single()
        val decision = policy.evaluate(resolution.type, resolution.evidence)

        assertEquals(SourceRelationshipState.CANDIDATE, decision.state)
    }

    @Test
    fun sameExplicitActorIdWithinSameAccountConfirmsPerson() {
        val left = input(
            "left",
            metadata(
                provider = "contacts",
                account = "device",
                externalId = "row-1",
                actor = SourceActorMetadata(actorId = "contact-42", displayName = "Alice"),
            ),
        )
        val right = input(
            "right",
            metadata(
                provider = "contacts",
                account = "device",
                externalId = "row-2",
                actor = SourceActorMetadata(actorId = "contact-42", displayName = "A. Example"),
            ),
        )

        val resolution = PersonIdentityResolver().resolve(left, right).single()
        val decision = policy.evaluate(resolution.type, resolution.evidence)

        assertEquals(SourceRelationshipState.CONFIRMED, decision.state)
    }

    @Test
    fun differentExplicitActorIdsBlockNameBasedPersonMerge() {
        val left = input(
            "left",
            metadata(
                provider = "contacts",
                account = "device",
                externalId = "row-1",
                actor = SourceActorMetadata(actorId = "contact-1", displayName = "Thomas Müller"),
            ),
        )
        val right = input(
            "right",
            metadata(
                provider = "contacts",
                account = "device",
                externalId = "row-2",
                actor = SourceActorMetadata(actorId = "contact-2", displayName = "Thomas Müller"),
            ),
        )

        val resolution = PersonIdentityResolver().resolve(left, right).single()
        val decision = policy.evaluate(resolution.type, resolution.evidence)

        assertEquals(SourceRelationshipState.BLOCKED, decision.state)
        assertTrue(decision.negativeEvidence.isNotEmpty())
    }

    @Test
    fun explicitOrganizationIdConfirmsOrganizationButNameAloneDoesNot() {
        val left = input(
            "left",
            metadata(
                provider = "crm",
                account = "a",
                externalId = "org-left",
                attributes = mapOf(
                    "entity:organization-id" to "org-42",
                    "entity:organization-name" to "Example GmbH",
                ),
            ),
        )
        val right = input(
            "right",
            metadata(
                provider = "crm",
                account = "b",
                externalId = "org-right",
                attributes = mapOf(
                    "entity:organization-id" to "org-42",
                    "entity:organization-name" to "Example GmbH",
                ),
            ),
        )

        val resolution = OrganizationIdentityResolver().resolve(left, right).single()
        assertEquals(
            SourceRelationshipState.CONFIRMED,
            policy.evaluate(resolution.type, resolution.evidence).state,
        )

        val nameOnly = OrganizationIdentityResolver().resolve(
            input(
                "name-left",
                metadata(
                    provider = "crm",
                    account = "a",
                    externalId = "n1",
                    attributes = mapOf("entity:organization-name" to "Example GmbH"),
                ),
            ),
            input(
                "name-right",
                metadata(
                    provider = "crm",
                    account = "b",
                    externalId = "n2",
                    attributes = mapOf("entity:organization-name" to "Example GmbH"),
                ),
            ),
        ).single()
        assertEquals(
            SourceRelationshipState.CANDIDATE,
            policy.evaluate(nameOnly.type, nameOnly.evidence).state,
        )
    }

    private fun input(id: String, metadata: CanonicalSourceMetadata): SourceResolutionInput =
        SourceResolutionInput(
            sourceRef = PhotonRevisionRef(PhotonId(id), 1),
            metadata = metadata,
        )

    private fun metadata(
        provider: String,
        account: String,
        externalId: String,
        version: String = "v1",
        actor: SourceActorMetadata? = null,
        attributes: Map<String, String> = emptyMap(),
    ): CanonicalSourceMetadata = CanonicalSourceMetadata(
        objectKind = SourceObjectKind.CONTACT,
        origin = SourceMetadataOrigin.CONNECTOR,
        privacyZone = SourcePrivacyZone.PRIVATE,
        externalObject = SourceExternalObjectRef(
            provider = SourceProviderRef(provider),
            account = SourceAccountRef(provider, account),
            objectKind = SourceObjectKind.CONTACT,
            externalId = externalId,
            externalVersion = version,
        ),
        actor = actor,
        technical = SourceTechnicalMetadata(attributes = attributes),
    )
}
