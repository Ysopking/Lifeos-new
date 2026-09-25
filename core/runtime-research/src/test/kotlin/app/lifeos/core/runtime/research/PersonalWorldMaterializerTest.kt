package app.lifeos.core.runtime.research

import app.lifeos.core.field.EvidenceKind
import app.lifeos.core.field.EvidencePayload
import app.lifeos.core.field.EvidenceReliability
import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.life.InformationObservationId
import app.lifeos.core.runtime.life.SemanticEvidenceCandidate
import app.lifeos.core.runtime.life.SemanticProjectionResult
import app.lifeos.core.runtime.reasoning.TemporalEpisodeGraph
import app.lifeos.core.runtime.reasoning.TemporalEpisodeNode
import app.lifeos.core.runtime.reasoning.TemporalEpisodeNodeKind
import app.lifeos.core.runtime.world.StateDimensionId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PersonalWorldMaterializerTest {
    @Test
    fun materializationSeparatesFinanceConversationAndLifeFingerprints() {
        val finance = projection(
            domain = "finance",
            dimension = "finance.account.balance",
            seed = "finance",
        )
        val communication = projection(
            domain = "communication",
            dimension = "communication.notification.current",
            seed = "message",
        )
        val episode = episode()

        val snapshot = PersonalWorldMaterializer().materialize(
            semanticProjections = listOf(communication, finance),
            temporalEpisodes = listOf(episode),
            asOf = NOW,
            revision = 1L,
        )

        assertNotNull(snapshot.financialStateFingerprint)
        assertNotNull(snapshot.conversationStateFingerprint)
        assertNull(snapshot.relationshipStateFingerprint)
        assertEquals(listOf(episode.id), snapshot.temporalEpisodeIds)
        assertFalse(snapshot.directWorldStateMutationAllowed)
        assertFalse(snapshot.effectAuthority)
    }

    private fun projection(
        domain: String,
        dimension: String,
        seed: String,
    ): SemanticProjectionResult {
        val source = Photon(
            id = PhotonId("source-$seed"),
            revision = 1L,
            content = seed,
            mimeType = "text/plain",
            semanticMass = 0.5,
            energy = 0.5,
            confidence = 1.0,
            provenance = Provenance(
                source = "test",
                actor = "test",
                createdAt = NOW,
            ),
        )
        val candidate = SemanticEvidenceCandidate(
            stateDimension = StateDimensionId(dimension),
            semanticKey = seed,
            kind = EvidenceKind.OBSERVATION,
            confidence = 0.8,
            reliability = EvidenceReliability(0.8, "test"),
            payload = EvidencePayload("test", mapOf("seed" to seed)),
            explanation = "test",
        )
        return SemanticProjectionResult.create(
            projectorId = "projector-$seed",
            domainId = FieldDomainId(domain),
            sourceObservationId = InformationObservationId(
                InformationObservationId.PREFIX + "a".repeat(64)
            ),
            sourcePhoton = source,
            candidates = listOf(candidate),
        )
    }

    private fun episode(): TemporalEpisodeGraph {
        val node = TemporalEpisodeNode.create(
            kind = TemporalEpisodeNodeKind.OBSERVATION,
            sourceRef = "episode-source",
            occurredAt = NOW,
            payloadFingerprint = "b".repeat(64),
        )
        return TemporalEpisodeGraph.create(
            nodes = listOf(node),
            edges = emptyList(),
        )
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-25T06:00:00Z")
    }
}
