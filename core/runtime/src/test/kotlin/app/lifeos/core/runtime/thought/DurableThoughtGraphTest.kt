package app.lifeos.core.runtime.thought

import app.lifeos.core.field.TemporalValidity
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DurableThoughtGraphTest {
    private val t0 = Instant.parse("2026-09-11T06:30:00Z")

    @Test
    fun deltaCodecRoundTripsCanonicalNodeEdgeAndProvenance() {
        val source = node("source", revision = 1, summary = "source")
        val target = node("target", revision = 1, summary = "target")
        val edge = edge(source, target, revision = 1)
        val delta = ThoughtGraphDelta.create(
            sourceKey = "codec-source",
            sourceRevision = 1,
            nodeVersions = listOf(target, source),
            edgeVersions = listOf(edge),
            observedAt = t0,
        )

        val decoded = ThoughtGraphDeltaCodec.decode(ThoughtGraphDeltaCodec.encode(delta))

        assertEquals(delta, decoded)
        assertEquals(delta.id, decoded.id)
        assertEquals(source.attributes, decoded.nodeVersions.first { it.id == source.id }.attributes)
    }

    @Test
    fun appendPersistsBeforeRamAndExactReplayDoesNotAdvanceRevision() = runTest {
        val repository = InMemoryRepository()
        val graph = DurableThoughtGraph(repository)
        val first = delta("persistent", observedAt = t0)

        val applied = graph.append(first, t0)
        val replay = graph.append(first.copy(observedAt = t0.plusSeconds(20)), t0.plusSeconds(20))

        assertEquals(1L, applied.state.revision)
        assertTrue(replay.replayed)
        assertEquals(1L, graph.state.value.revision)
        assertEquals(1, repository.deltas.size)
        assertEquals(t0, repository.deltas.single().observedAt)
    }

    @Test
    fun repositoryFailureCannotMutateInMemoryGraph() = runTest {
        val graph = DurableThoughtGraph(
            object : ThoughtGraphDeltaRepository {
                override suspend fun save(delta: ThoughtGraphDelta): ThoughtGraphDeltaWriteResult =
                    error("vault-write-failed")

                override suspend fun load(id: ThoughtGraphDeltaId): ThoughtGraphDelta? = null

                override suspend fun loadReport(): ThoughtGraphDeltaLoadReport =
                    ThoughtGraphDeltaLoadReport(emptyList(), emptyList())
            }
        )

        assertFailsWith<IllegalStateException> {
            graph.append(delta("must-not-apply", observedAt = t0), t0)
        }
        assertEquals(0L, graph.state.value.revision)
        assertTrue(graph.state.value.nodeVersions.isEmpty())
    }

    @Test
    fun rehydrateReplaysCompleteHistoryToSameSnapshot() = runTest {
        val repository = InMemoryRepository()
        val firstGraph = DurableThoughtGraph(repository)
        val first = delta("first", observedAt = t0)
        val second = delta("second", observedAt = t0.plusSeconds(1))
        firstGraph.append(second, t0.plusSeconds(1))
        firstGraph.append(first, t0)
        val before = firstGraph.snapshot(t0.plusSeconds(2))

        val restored = DurableThoughtGraph(repository)
        val report = restored.rehydrate(t0.plusSeconds(2))
        val after = restored.snapshot(t0.plusSeconds(2))

        assertEquals(2, report.restoredDeltaCount)
        assertEquals(2L, report.revision)
        assertEquals(before.historyFingerprint, after.historyFingerprint)
        assertEquals(before.contentFingerprint, after.contentFingerprint)
        assertEquals(before.activeNodes.map { it.fingerprint }, after.activeNodes.map { it.fingerprint })
    }

    @Test
    fun unreadableHistoryFailsClosedWithoutPublishingPartialState() = runTest {
        val readable = delta("readable", observedAt = t0)
        val graph = DurableThoughtGraph(
            object : ThoughtGraphDeltaRepository {
                override suspend fun save(delta: ThoughtGraphDelta): ThoughtGraphDeltaWriteResult =
                    ThoughtGraphDeltaWriteResult.Stored(delta)

                override suspend fun load(id: ThoughtGraphDeltaId): ThoughtGraphDelta? = null

                override suspend fun loadReport(): ThoughtGraphDeltaLoadReport =
                    ThoughtGraphDeltaLoadReport(
                        deltas = listOf(readable),
                        unreadableEntries = listOf("corrupt.tgdelta"),
                    )
            }
        )

        val failure = assertFailsWith<IllegalArgumentException> { graph.rehydrate(t0) }

        assertTrue(failure.message.orEmpty().contains("unreadable"))
        assertEquals(0L, graph.state.value.revision)
        assertTrue(graph.state.value.nodeVersions.isEmpty())
    }

    @Test
    fun duplicateWriteResultUsesPersistedEnvelopeNotRetryTimestamp() = runTest {
        val repository = InMemoryRepository()
        val graph = DurableThoughtGraph(repository)
        val first = delta("stable-envelope", observedAt = t0)
        graph.append(first, t0)
        val retry = first.copy(observedAt = t0.plusSeconds(99))

        val result = repository.save(retry)

        assertIs<ThoughtGraphDeltaWriteResult.Duplicate>(result)
        assertEquals(t0, result.delta.observedAt)
        assertFalse(result.delta.observedAt == retry.observedAt)
    }

    private fun delta(source: String, observedAt: Instant): ThoughtGraphDelta = ThoughtGraphDelta.create(
        sourceKey = source,
        sourceRevision = 1,
        nodeVersions = listOf(node(source, 1, "$source summary")),
        observedAt = observedAt,
    )

    private fun node(source: String, revision: Long, summary: String): ThoughtGraphNodeVersion {
        val provenance = ThoughtGraphProvenance(
            sourceKind = ThoughtGraphSourceKind.PHOTON,
            sourceId = "photon:$source",
            sourceRevision = revision,
            sourceFingerprint = "fingerprint:$source:$revision",
            origin = "test",
            actor = "DurableThoughtGraphTest",
            createdAt = t0.plusSeconds(revision),
        )
        return ThoughtGraphNodeVersion.create(
            kind = ThoughtGraphNodeKind.PHOTON,
            semanticKey = source,
            summary = summary,
            confidence = 0.8,
            authority = 0.7,
            validity = TemporalValidity(t0, t0.plusSeconds(3600)),
            provenance = provenance,
            attributes = mapOf("source" to source, "revision" to revision.toString()),
        )
    }

    private fun edge(
        source: ThoughtGraphNodeVersion,
        target: ThoughtGraphNodeVersion,
        revision: Long,
    ): ThoughtGraphEdgeVersion = ThoughtGraphEdgeVersion.create(
        sourceNodeId = source.id,
        targetNodeId = target.id,
        kind = ThoughtGraphEdgeKind.REFERENCES,
        semanticKey = "reference",
        confidence = 0.75,
        authority = 0.65,
        validity = TemporalValidity.UNBOUNDED,
        provenance = ThoughtGraphProvenance(
            sourceKind = ThoughtGraphSourceKind.SYSTEM,
            sourceId = "edge-source",
            sourceRevision = revision,
            sourceFingerprint = "edge-fingerprint:$revision",
            origin = "test",
            actor = "DurableThoughtGraphTest",
            createdAt = t0.plusSeconds(revision),
        ),
        explanation = "source references target",
    )

    private class InMemoryRepository : ThoughtGraphDeltaRepository {
        val deltas = mutableListOf<ThoughtGraphDelta>()

        override suspend fun save(delta: ThoughtGraphDelta): ThoughtGraphDeltaWriteResult {
            val existing = deltas.firstOrNull { it.id == delta.id }
            if (existing != null) {
                require(
                    existing.sourceKey == delta.sourceKey &&
                        existing.sourceRevision == delta.sourceRevision &&
                        existing.nodeVersions == delta.nodeVersions &&
                        existing.edgeVersions == delta.edgeVersions
                )
                return ThoughtGraphDeltaWriteResult.Duplicate(existing)
            }
            deltas += ThoughtGraphDeltaCodec.decode(ThoughtGraphDeltaCodec.encode(delta))
            return ThoughtGraphDeltaWriteResult.Stored(deltas.last())
        }

        override suspend fun load(id: ThoughtGraphDeltaId): ThoughtGraphDelta? =
            deltas.firstOrNull { it.id == id }

        override suspend fun loadReport(): ThoughtGraphDeltaLoadReport = ThoughtGraphDeltaLoadReport(
            deltas = deltas.distinctBy { it.id }.sortedBy { it.id.value },
            unreadableEntries = emptyList(),
        )
    }
}
