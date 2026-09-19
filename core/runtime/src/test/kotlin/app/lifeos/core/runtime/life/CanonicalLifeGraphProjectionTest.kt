package app.lifeos.core.runtime.life

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.source.CanonicalSourceMetadata
import app.lifeos.core.model.source.SourceAccountRef
import app.lifeos.core.model.source.SourceExternalObjectRef
import app.lifeos.core.model.source.SourceMetadataOrigin
import app.lifeos.core.model.source.SourceObjectKind
import app.lifeos.core.model.source.SourcePrivacyZone
import app.lifeos.core.model.source.SourceProjectHint
import app.lifeos.core.model.source.SourceProviderRef
import app.lifeos.core.runtime.source.SourceMetadataPhotonFactory
import app.lifeos.core.runtime.sourcegraph.EvidencePolarity
import app.lifeos.core.runtime.sourcegraph.EvidenceStrength
import app.lifeos.core.runtime.sourcegraph.PhotonBackedSourceRelationshipRepository
import app.lifeos.core.runtime.sourcegraph.RelationshipEvidenceFamily
import app.lifeos.core.runtime.sourcegraph.RelationshipEvidenceKind
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipCodec
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipEdge
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipEvidence
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipState
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipType
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CanonicalLifeGraphProjectionTest {
    private val at = Instant.parse("2026-09-19T13:00:00Z")

    @Test
    fun confirmedLedgerEdgeProjectsExactRevisionEvidenceIntoLifeGraphV3() {
        val message = sourcePhoton("message")
        val project = sourcePhoton("project")
        val messageMetadata = SourceMetadataPhotonFactory.create(
            message,
            metadata(
                kind = SourceObjectKind.EMAIL,
                provider = "mail",
                account = "mail-account",
                externalId = "message-1",
            ),
        )
        val projectMetadata = SourceMetadataPhotonFactory.create(
            project,
            metadata(
                kind = SourceObjectKind.PROJECT,
                provider = "projects",
                account = "project-account",
                externalId = "project-1",
                projectName = "LifeOS",
            ),
        )
        val sourceRef = PhotonRevisionRef(message.id, message.revision)
        val targetRef = PhotonRevisionRef(project.id, project.revision)
        val evidence = SourceRelationshipEvidence.create(
            family = RelationshipEvidenceFamily.IDENTITY,
            kind = RelationshipEvidenceKind.PROJECT_ID,
            strength = EvidenceStrength.DETERMINISTIC,
            polarity = EvidencePolarity.POSITIVE,
            sourceRef = sourceRef,
            confidence = 1.0,
            explanation = "explicit project id",
        )
        val edge = SourceRelationshipEdge(
            source = sourceRef,
            target = targetRef,
            type = SourceRelationshipType.BELONGS_TO_PROJECT,
            state = SourceRelationshipState.CONFIRMED,
            confidence = 1.0,
            positiveEvidence = listOf(evidence),
            negativeEvidence = emptyList(),
            blockers = emptyList(),
            resolverId = "test-resolver",
            resolverVersion = "1",
            createdAt = at,
            lastEvaluatedAt = at,
        )
        val relationshipPhoton = Photon(
            id = PhotonId(edge.edgeId),
            content = SourceRelationshipCodec.encode(edge),
            mimeType = PhotonBackedSourceRelationshipRepository.MIME_TYPE,
            confidence = 1.0,
            provenance = Provenance(
                source = "source-relationship-ledger",
                actor = "test-resolver",
                createdAt = at,
            ),
            tags = setOf(PhotonBackedSourceRelationshipRepository.ROOT_TAG),
        )

        val graph = LifeGraphProjector().project(
            listOf(
                message,
                project,
                messageMetadata,
                projectMetadata,
                relationshipPhoton,
            )
        )

        val relationship = graph.relationships.single { it.canonicalType != null }
        assertEquals(SourceRelationshipType.BELONGS_TO_PROJECT, relationship.canonicalType)
        assertEquals(SourceRelationshipState.CONFIRMED, relationship.canonicalState)
        assertEquals(edge.fingerprint, relationship.evidenceFingerprint)
        assertTrue(sourceRef in relationship.sourceRevisionRefs)
        assertTrue(targetRef in relationship.sourceRevisionRefs)
        assertTrue(relationshipPhoton.id in relationship.sourcePhotonIds)
        assertNotNull(graph.entities.singleOrNull { it.type == LifeEntityType.PROJECT && it.label == "LifeOS" })
    }

    @Test
    fun candidateLedgerEdgeDoesNotEnterCanonicalProjection() {
        val left = sourcePhoton("left")
        val right = sourcePhoton("right")
        val leftRef = PhotonRevisionRef(left.id, left.revision)
        val rightRef = PhotonRevisionRef(right.id, right.revision)
        val evidence = SourceRelationshipEvidence.create(
            family = RelationshipEvidenceFamily.SEMANTIC,
            kind = RelationshipEvidenceKind.SEMANTIC_SIMILARITY,
            strength = EvidenceStrength.WEAK,
            polarity = EvidencePolarity.POSITIVE,
            sourceRef = leftRef,
            confidence = 0.4,
            explanation = "topic overlap",
        )
        val edge = SourceRelationshipEdge(
            source = leftRef,
            target = rightRef,
            type = SourceRelationshipType.SAME_PROJECT,
            state = SourceRelationshipState.CANDIDATE,
            confidence = 0.4,
            positiveEvidence = listOf(evidence),
            negativeEvidence = emptyList(),
            blockers = emptyList(),
            resolverId = "test-resolver",
            resolverVersion = "1",
            createdAt = at,
            lastEvaluatedAt = at,
        )
        val relationshipPhoton = Photon(
            id = PhotonId(edge.edgeId),
            content = SourceRelationshipCodec.encode(edge),
            mimeType = PhotonBackedSourceRelationshipRepository.MIME_TYPE,
            confidence = edge.confidence,
            provenance = Provenance("source-relationship-ledger", "test-resolver", at),
            tags = setOf(PhotonBackedSourceRelationshipRepository.ROOT_TAG),
        )

        val graph = LifeGraphProjector().project(listOf(left, right, relationshipPhoton))

        assertTrue(graph.relationships.none { it.canonicalType == SourceRelationshipType.SAME_PROJECT })
    }

    private fun sourcePhoton(id: String): Photon = Photon(
        id = PhotonId(id),
        content = id,
        provenance = Provenance("test", "user", at),
    )

    private fun metadata(
        kind: SourceObjectKind,
        provider: String,
        account: String,
        externalId: String,
        projectName: String? = null,
    ): CanonicalSourceMetadata = CanonicalSourceMetadata(
        objectKind = kind,
        origin = SourceMetadataOrigin.CONNECTOR,
        privacyZone = SourcePrivacyZone.PRIVATE,
        externalObject = SourceExternalObjectRef(
            provider = SourceProviderRef(provider),
            account = SourceAccountRef(provider, account),
            objectKind = kind,
            externalId = externalId,
            externalVersion = "v1",
        ),
        projectHint = projectName?.let {
            SourceProjectHint(
                explicitProjectId = externalId,
                projectName = it,
            )
        },
    )
}
