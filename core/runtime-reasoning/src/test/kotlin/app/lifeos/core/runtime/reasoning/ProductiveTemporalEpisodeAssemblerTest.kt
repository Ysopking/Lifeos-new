package app.lifeos.core.runtime.reasoning

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProductiveTemporalEpisodeAssemblerTest {
    @Test
    fun chronologyAndExplicitVerificationRemainDeterministicAndNonCausal() {
        val observation = node("observation", TemporalEpisodeNodeKind.OBSERVATION, 0)
        val action = node("action", TemporalEpisodeNodeKind.ACTION, 1)
        val receipt = node("receipt", TemporalEpisodeNodeKind.RECEIPT, 2)
        val outcome = node("outcome", TemporalEpisodeNodeKind.OUTCOME, 3)

        val input = ProductiveTemporalEpisodeInput(
            observations = listOf(observation),
            actions = listOf(action),
            receipts = listOf(receipt),
            outcomes = listOf(outcome),
            explicitRelations = listOf(
                ProductiveTemporalRelation(
                    source = action.id,
                    target = receipt.id,
                    kind = TemporalEpisodeEdgeKind.RECEIPT_FOR,
                    evidenceFingerprint = "a".repeat(64),
                ),
                ProductiveTemporalRelation(
                    source = receipt.id,
                    target = outcome.id,
                    kind = TemporalEpisodeEdgeKind.VERIFIES,
                    evidenceFingerprint = "b".repeat(64),
                ),
            ),
        )

        val first = ProductiveTemporalEpisodeAssembler().assemble(input)
        val second = ProductiveTemporalEpisodeAssembler().assemble(input)

        assertEquals(first, second)
        assertFalse(first.causalClaimsAllowed)
        assertEquals(3, first.edges.count { it.kind == TemporalEpisodeEdgeKind.PRECEDES })
        assertTrue(first.edges.any { it.kind == TemporalEpisodeEdgeKind.RECEIPT_FOR })
        assertTrue(first.edges.any { it.kind == TemporalEpisodeEdgeKind.VERIFIES })
    }

    private fun node(
        ref: String,
        kind: TemporalEpisodeNodeKind,
        seconds: Long,
    ) = TemporalEpisodeNode.create(
        kind = kind,
        sourceRef = ref,
        occurredAt = Instant.parse("2026-09-25T05:00:00Z").plusSeconds(seconds),
        payloadFingerprint = ref.padEnd(64, '0').take(64),
    )
}
