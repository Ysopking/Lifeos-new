package app.lifeos.core.runtime.thought

import app.lifeos.core.field.TemporalValidity
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class ThoughtGraphCompactionTest {
    private val baseTime = Instant.parse("2026-09-11T11:00:00Z")

    @Test
    fun `segment codec round trip preserves every exact delta`() {
        val deltas = listOf(delta("a", 1), delta("b", 2), delta("c", 3))
        val segment = ThoughtGraphDeltaSegment.create(deltas.reversed())

        val decoded = ThoughtGraphDeltaSegmentCodec.decode(
            ThoughtGraphDeltaSegmentCodec.encode(segment),
        )

        assertEquals(segment, decoded)
        assertEquals(deltas.map { it.id }.sortedBy { it.value }, decoded.deltas.map { it.id })
    }

    @Test
    fun `segment identity includes canonical persisted observation time`() {
        val first = delta("same", 1)
        val laterObservation = first.copy(observedAt = first.observedAt.plusSeconds(30))
        val companion = delta("other", 2)

        val firstSegment = ThoughtGraphDeltaSegment.create(listOf(first, companion))
        val laterSegment = ThoughtGraphDeltaSegment.create(listOf(laterObservation, companion))

        assertEquals(first.id, laterObservation.id)
        assertNotEquals(firstSegment.id, laterSegment.id)
    }

    @Test
    fun `segment rejects duplicate delta identities`() {
        val first = delta("duplicate", 1)
        assertFailsWith<IllegalArgumentException> {
            ThoughtGraphDeltaSegment(
                id = ThoughtGraphDeltaSegment.create(listOf(first, delta("other", 2))).id,
                deltas = listOf(first, first),
            )
        }
    }

    private fun delta(source: String, second: Long): ThoughtGraphDelta {
        val observedAt = baseTime.plusSeconds(second)
        val provenance = ThoughtGraphProvenance(
            sourceKind = ThoughtGraphSourceKind.SYSTEM,
            sourceId = "compaction:$source",
            sourceRevision = 1,
            sourceFingerprint = "fingerprint:$source",
            origin = "thought-graph-compaction-test",
            actor = "test",
            createdAt = observedAt,
        )
        val node = ThoughtGraphNodeVersion.create(
            kind = ThoughtGraphNodeKind.PHOTON,
            semanticKey = "compaction:$source",
            summary = "compaction $source",
            confidence = 0.8,
            authority = 0.7,
            validity = TemporalValidity.at(observedAt),
            provenance = provenance,
        )
        return ThoughtGraphDelta.create(
            sourceKey = "compaction-test:$source",
            sourceRevision = 1,
            nodeVersions = listOf(node),
            observedAt = observedAt,
        )
    }
}
