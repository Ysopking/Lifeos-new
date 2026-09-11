package app.lifeos.core.runtime.trace

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DecisionTraceCodecTest {
    @Test
    fun `round trip is deterministic and preserves exact metadata`() {
        val firstId = DecisionTraceId.create("goal", "Goal-7")
        val secondId = DecisionTraceId.create("goal", "goal-8")
        val firstNode = DecisionTraceNode.create(
            type = DecisionTraceNodeType.OBSERVED_FACT,
            sourceType = "goal",
            sourceId = "Goal-7",
            sourceRevision = 1,
            displayLabel = "Exact Label",
            recordedAt = NOW,
        )
        val secondNode = DecisionTraceNode.create(
            type = DecisionTraceNodeType.REJECTION,
            sourceType = "owner-policy",
            sourceId = "decision:7",
            sourceRevision = 4,
            reasonCodes = listOf("EFFECT_NOT_GRANTED", "RESOURCE_NOT_GRANTED"),
            recordedAt = NOW.plusSeconds(1),
        )
        val revisions = listOf(
            DecisionTrace(firstId, 1, listOf(firstNode), emptyList()),
            DecisionTrace(firstId, 2, listOf(firstNode, secondNode), listOf(
                DecisionTraceLink(firstNode.id, secondNode.id, DecisionTraceLinkType.REJECTED_BY),
            )),
            DecisionTrace(secondId, 1, listOf(secondNode), emptyList()),
        )

        val firstEncoding = DecisionTraceLogCodec.encode(revisions.reversed())
        val secondEncoding = DecisionTraceLogCodec.encode(revisions)
        assertContentEquals(secondEncoding, firstEncoding)
        assertEquals(revisions, DecisionTraceLogCodec.decode(secondEncoding))
    }

    @Test
    fun `revision gaps are rejected`() {
        val id = DecisionTraceId.create("goal", "goal-gap")
        val node = DecisionTraceNode.create(
            DecisionTraceNodeType.OBSERVED_FACT, "goal", "goal-gap", 1, recordedAt = NOW,
        )
        assertFailsWith<IllegalArgumentException> {
            DecisionTraceLogCodec.encode(listOf(DecisionTrace(id, 2, listOf(node), emptyList())))
        }
    }

    @Test
    fun `unsupported version and trailing bytes are rejected`() {
        val id = DecisionTraceId.create("goal", "goal-codec")
        val node = DecisionTraceNode.create(
            DecisionTraceNodeType.OBSERVED_FACT, "goal", "goal-codec", 1, recordedAt = NOW,
        )
        val valid = DecisionTraceLogCodec.encode(listOf(DecisionTrace(id, 1, listOf(node), emptyList())))

        val unsupported = valid.copyOf().also { bytes -> bytes[7] = 2 }
        assertFailsWith<IllegalArgumentException> { DecisionTraceLogCodec.decode(unsupported) }

        val trailing = valid + byteArrayOf(0x01)
        assertFailsWith<IllegalArgumentException> { DecisionTraceLogCodec.decode(trailing) }
    }

    @Test
    fun `truncation is rejected`() {
        val id = DecisionTraceId.create("goal", "goal-truncated")
        val node = DecisionTraceNode.create(
            DecisionTraceNodeType.OBSERVED_FACT, "goal", "goal-truncated", 1, recordedAt = NOW,
        )
        val valid = DecisionTraceLogCodec.encode(listOf(DecisionTrace(id, 1, listOf(node), emptyList())))
        assertFailsWith<Exception> { DecisionTraceLogCodec.decode(valid.copyOf(valid.size - 3)) }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-11T20:00:00Z")
    }
}
