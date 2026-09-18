package app.lifeos.core.field

import app.lifeos.core.model.PhotonId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FieldConvergenceEngineTest {
    private val now = Instant.parse("2026-09-08T12:00:00Z")
    private val domain = StableFieldIds.domain("test.domain")
    private val context = FieldContext(TemporalContext(now), DomainContext(domain))
    private val engine = FieldConvergenceEngine(
        config = ConvergenceConfig(
            maxIterations = 12,
            requiredStableRounds = 2,
            epsilon = 0.01,
            minConvergence = 0.50,
            minWinnerMargin = 0.08,
            damping = 0.55,
        ),
    )

    @Test
    fun `stronger evidence converges to one winner`() {
        val strong = evidence("strong", 0.98, 0.98, SourceAuthority.AUTHORITATIVE)
        val weak = evidence("weak", 0.30, 0.30, SourceAuthority.UNVERIFIED)
        val strongNode = FieldNode.create(
            domain, FieldNodeKind.HYPOTHESIS, "strong", evidenceIds = setOf(strong.id),
        )
        val weakNode = FieldNode.create(
            domain, FieldNodeKind.HYPOTHESIS, "weak", evidenceIds = setOf(weak.id),
        )
        val hypotheses = listOf(
            hypothesis("strong", strongNode.id, strong.id),
            hypothesis("weak", weakNode.id, weak.id),
        )
        val request = FieldConvergenceRequest(
            domainId = domain,
            graph = FieldGraph(
                domainId = domain,
                nodes = listOf(strongNode, weakNode),
                competitionGroups = listOf(CompetitionGroup("answer", setOf(strongNode.id, weakNode.id))),
            ),
            evidence = listOf(weak, strong),
            hypotheses = hypotheses,
            context = context,
        )

        val result = engine.converge(request)

        assertEquals(ConvergenceStatus.CONVERGED, result.status)
        val winner = assertNotNull(result.winner)
        assertEquals("strong", winner.semanticKey)
        assertTrue(winner.score.temporal > 0.0)
        assertEquals(SourceAuthority.AUTHORITATIVE.defaultWeight, winner.score.authority)
        assertTrue(result.iterations <= 12)
        assertTrue(result.hypotheses.first().score.total > result.hypotheses.last().score.total)
    }

    @Test
    fun `equal hypotheses remain explicitly unresolved`() {
        val shared = evidence("shared", 0.85, 0.85, SourceAuthority.DOCUMENTED)
        val aNode = FieldNode.create(domain, FieldNodeKind.HYPOTHESIS, "a", evidenceIds = setOf(shared.id))
        val bNode = FieldNode.create(domain, FieldNodeKind.HYPOTHESIS, "b", evidenceIds = setOf(shared.id))
        val request = FieldConvergenceRequest(
            domainId = domain,
            graph = FieldGraph(
                domain,
                listOf(aNode, bNode),
                competitionGroups = listOf(CompetitionGroup("meaning", setOf(aNode.id, bNode.id))),
            ),
            evidence = listOf(shared),
            hypotheses = listOf(
                hypothesis("a", aNode.id, shared.id),
                hypothesis("b", bNode.id, shared.id),
            ),
            context = context,
        )

        val result = engine.converge(request)

        assertEquals(ConvergenceStatus.UNRESOLVED, result.status)
        assertNull(result.winner)
        assertEquals(2, result.hypotheses.count { it.state == HypothesisState.UNRESOLVED })
        assertEquals(result.hypotheses[0].score.total, result.hypotheses[1].score.total)
    }

    @Test
    fun `convergence is deterministic across repeated runs and input ordering`() {
        val firstEvidence = evidence("first", 0.9, 0.9, SourceAuthority.OFFICIAL)
        val secondEvidence = evidence("second", 0.5, 0.6, SourceAuthority.DOCUMENTED)
        val firstNode = FieldNode.create(domain, FieldNodeKind.HYPOTHESIS, "first", evidenceIds = setOf(firstEvidence.id))
        val secondNode = FieldNode.create(domain, FieldNodeKind.HYPOTHESIS, "second", evidenceIds = setOf(secondEvidence.id))
        val firstHypothesis = hypothesis("first", firstNode.id, firstEvidence.id)
        val secondHypothesis = hypothesis("second", secondNode.id, secondEvidence.id)
        val graph = FieldGraph(domain, listOf(secondNode, firstNode))

        val first = engine.converge(
            FieldConvergenceRequest(
                domain, graph, listOf(firstEvidence, secondEvidence),
                listOf(firstHypothesis, secondHypothesis), context,
            ),
        )
        val second = engine.converge(
            FieldConvergenceRequest(
                domain, graph, listOf(secondEvidence, firstEvidence),
                listOf(secondHypothesis, firstHypothesis), context,
            ),
        )

        assertEquals(first, second)
    }

    @Test
    fun `source evidence objects are unchanged by convergence`() {
        val source = evidence("immutable", 0.9, 0.9, SourceAuthority.OFFICIAL)
        val sourceCopy = source.copy()
        val node = FieldNode.create(domain, FieldNodeKind.HYPOTHESIS, "only", evidenceIds = setOf(source.id))
        val request = FieldConvergenceRequest(
            domainId = domain,
            graph = FieldGraph(domain, listOf(node)),
            evidence = listOf(source),
            hypotheses = listOf(hypothesis("only", node.id, source.id)),
            context = context,
        )

        engine.converge(request)
        assertEquals(sourceCopy, source)
    }

    private fun evidence(
        key: String,
        confidence: Double,
        reliability: Double,
        authority: SourceAuthority,
    ): FieldEvidence = FieldEvidence.create(
        domainId = domain,
        sourcePhotonId = PhotonId("photon-$key"),
        sourceRevision = 1,
        kind = EvidenceKind.ASSERTION,
        semanticKey = key,
        confidence = confidence,
        reliability = EvidenceReliability(reliability, "test-$key"),
        authority = authority,
        observedAt = now.minusSeconds(60),
        payload = EvidencePayload.text(key),
        explanation = "Evidence $key",
    )

    private fun hypothesis(
        key: String,
        nodeId: FieldNodeId,
        evidenceId: EvidenceId,
    ): FieldHypothesis = FieldHypothesis.create(
        domainId = domain,
        semanticKey = key,
        scope = HypothesisScope.DOMAIN,
        nodeIds = setOf(nodeId),
        evidenceLinks = listOf(HypothesisEvidenceLink(evidenceId, EvidenceRelationType.SUPPORTS, 1.0)),
        explanation = "Hypothesis $key",
    )
}
