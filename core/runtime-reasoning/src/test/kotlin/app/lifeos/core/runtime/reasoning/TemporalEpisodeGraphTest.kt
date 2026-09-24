package app.lifeos.core.runtime.reasoning

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemporalEpisodeGraphTest {
    private val t0 = Instant.parse("2026-09-24T10:00:00Z")

    @Test
    fun graphOrderIsDeterministicAcrossInputOrdering() {
        val action = node(
            TemporalEpisodeNodeKind.ACTION,
            "action:1",
            t0.plusSeconds(10),
        )
        val receipt = node(
            TemporalEpisodeNodeKind.RECEIPT,
            "receipt:1",
            t0.plusSeconds(11),
        )
        val edge = TemporalEpisodeEdge.create(
            source = action.id,
            target = receipt.id,
            kind = TemporalEpisodeEdgeKind.RECEIPT_FOR,
            provenanceFingerprint = "receipt-link",
        )

        val first = TemporalEpisodeGraph.create(
            nodes = listOf(receipt, action),
            edges = listOf(edge),
        )
        val second = TemporalEpisodeGraph.create(
            nodes = listOf(action, receipt),
            edges = listOf(edge),
        )

        assertEquals(first, second)
        assertEquals(action.id, first.nodes.first().id)
        assertFalse(first.causalClaimsAllowed)
    }

    @Test
    fun chronologyCannotRunBackwards() {
        val earlier = node(
            TemporalEpisodeNodeKind.OBSERVATION,
            "observation:1",
            t0,
        )
        val later = node(
            TemporalEpisodeNodeKind.ACTION,
            "action:1",
            t0.plusSeconds(60),
        )
        val invalid = TemporalEpisodeEdge.create(
            source = later.id,
            target = earlier.id,
            kind = TemporalEpisodeEdgeKind.PRECEDES,
            provenanceFingerprint = "clock",
        )

        assertFailsWith<IllegalArgumentException> {
            TemporalEpisodeGraph.create(
                nodes = listOf(earlier, later),
                edges = listOf(invalid),
            )
        }
    }

    @Test
    fun actionReceiptObservationOutcomeCanBeLinkedWithoutCausalClaim() {
        val action = node(TemporalEpisodeNodeKind.ACTION, "action:1", t0)
        val receipt = node(
            TemporalEpisodeNodeKind.RECEIPT,
            "receipt:1",
            t0.plusSeconds(1),
        )
        val observation = node(
            TemporalEpisodeNodeKind.OBSERVATION,
            "observation:1",
            t0.plusSeconds(5),
        )
        val outcome = node(
            TemporalEpisodeNodeKind.OUTCOME,
            "outcome:1",
            t0.plusSeconds(5),
        )

        val graph = TemporalEpisodeGraph.create(
            nodes = listOf(action, receipt, observation, outcome),
            edges = listOf(
                TemporalEpisodeEdge.create(
                    receipt.id,
                    action.id,
                    TemporalEpisodeEdgeKind.RECEIPT_FOR,
                    "receipt",
                ),
                TemporalEpisodeEdge.create(
                    observation.id,
                    outcome.id,
                    TemporalEpisodeEdgeKind.VERIFIES,
                    "verification",
                ),
                TemporalEpisodeEdge.create(
                    action.id,
                    outcome.id,
                    TemporalEpisodeEdgeKind.EXPECTS,
                    "expectation",
                ),
            ),
        )

        assertTrue(graph.edges.any { it.kind == TemporalEpisodeEdgeKind.VERIFIES })
        assertFalse(
            TemporalEpisodeEdgeKind.entries.any { it.name == "CAUSES" }
        )
    }

    private fun node(
        kind: TemporalEpisodeNodeKind,
        ref: String,
        at: Instant,
    ) = TemporalEpisodeNode.create(
        kind = kind,
        sourceRef = ref,
        occurredAt = at,
        payloadFingerprint = "payload-$ref",
    )
}
