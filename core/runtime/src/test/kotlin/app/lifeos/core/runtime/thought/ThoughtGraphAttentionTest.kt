package app.lifeos.core.runtime.thought

import app.lifeos.core.field.TemporalValidity
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ThoughtGraphAttentionTest {
    private val baseTime = Instant.parse("2026-09-11T09:45:00Z")
    private val projector = ThoughtGraphAttentionProjector()

    @Test
    fun `goal and unresolved hypothesis dominate bounded attention`() {
        val goal = node("goal", ThoughtGraphNodeKind.GOAL, confidence = 0.90, authority = 0.80)
        val evidence = node("evidence", ThoughtGraphNodeKind.EVIDENCE, confidence = 0.85, authority = 0.90)
        val unresolved = node(
            "hypothesis-unresolved",
            ThoughtGraphNodeKind.HYPOTHESIS,
            confidence = 0.92,
            authority = 0.82,
            attributes = mapOf("state" to "UNRESOLVED"),
        )
        val unrelated = node("unrelated", ThoughtGraphNodeKind.PHOTON, confidence = 1.0, authority = 1.0)
        val snapshot = snapshot(
            nodes = listOf(goal, evidence, unresolved, unrelated),
            edges = listOf(
                edge(evidence, goal, ThoughtGraphEdgeKind.DERIVED_FROM, "evidence-goal"),
                edge(evidence, unresolved, ThoughtGraphEdgeKind.SUPPORTS, "evidence-hypothesis"),
            ),
        )

        val working = projector.project(
            snapshot = snapshot,
            policy = ThoughtGraphAttentionPolicy(maxNodes = 4, maxEdges = 8, maxConflicts = 4, goalDepth = 2),
            asOf = baseTime,
        )

        assertEquals(goal.id, working.entries[0].nodeId)
        assertEquals(unresolved.id, working.entries[1].nodeId)
        assertTrue(ThoughtGraphAttentionReason.GOAL in working.entries[0].reasons)
        assertTrue(ThoughtGraphAttentionReason.UNRESOLVED in working.entries[1].reasons)
        assertTrue(ThoughtGraphAttentionReason.GOAL_NEIGHBOR in working.entries[1].reasons)
        assertTrue(working.entries.indexOfFirst { it.nodeId == unrelated.id } > 1)
    }

    @Test
    fun `working set is hard bounded and deterministic at scale`() {
        val nodes = (0 until 300)
            .map { index -> node("scale-$index", ThoughtGraphNodeKind.PHOTON, confidence = (index % 100) / 100.0) }
            .sortedBy { it.id.value }
        val edges = nodes.zipWithNext().mapIndexed { index, (source, target) ->
            edge(source, target, ThoughtGraphEdgeKind.REFERENCES, "scale-edge-$index")
        }
        val conflicts = (0 until 100)
            .map { index ->
                ThoughtGraphConflict.create(
                    subjectKind = ThoughtGraphConflictSubjectKind.NODE,
                    subjectId = "missing-subject-$index",
                    sourceRevision = (index + 1).toLong(),
                    variantFingerprints = listOf("variant-a-$index", "variant-b-$index"),
                )
            }
        val snapshot = snapshot(nodes, edges, conflicts)
        val policy = ThoughtGraphAttentionPolicy(
            maxNodes = 32,
            maxEdges = 20,
            maxConflicts = 7,
            goalDepth = 2,
        )

        val first = projector.project(snapshot, policy, baseTime)
        val second = projector.project(snapshot, policy, baseTime)

        assertEquals(32, first.nodes.size)
        assertTrue(first.edges.size <= 20)
        assertEquals(7, first.conflicts.size)
        assertEquals(first, second)
        assertEquals(first.fingerprint, second.fingerprint)
        assertEquals(snapshot.historyFingerprint, first.sourceHistoryFingerprint)
    }

    @Test
    fun `explicit asOf changes temporal attention without hidden clock state`() {
        val validFrom = baseTime.plusSeconds(10)
        val timed = node(
            id = "timed",
            kind = ThoughtGraphNodeKind.EVIDENCE,
            validity = TemporalValidity(validFrom = validFrom, validUntilExclusive = validFrom.plusSeconds(10)),
        )
        val snapshot = snapshot(listOf(timed))

        val before = projector.project(snapshot, asOf = baseTime)
        val inside = projector.project(snapshot, asOf = validFrom.plusSeconds(1))
        val insideAgain = projector.project(snapshot, asOf = validFrom.plusSeconds(1))

        assertTrue(ThoughtGraphAttentionReason.TEMPORALLY_VALID !in before.entries.single().reasons)
        assertTrue(ThoughtGraphAttentionReason.TEMPORALLY_VALID in inside.entries.single().reasons)
        assertEquals(inside, insideAgain)
        assertEquals(inside.fingerprint, insideAgain.fingerprint)
    }

    @Test
    fun `durable graph rebuild yields identical working set and attention never mutates history`() = runTest {
        val goal = node("durable-goal", ThoughtGraphNodeKind.GOAL)
        val evidence = node("durable-evidence", ThoughtGraphNodeKind.EVIDENCE)
        val hypothesis = node(
            "durable-hypothesis",
            ThoughtGraphNodeKind.HYPOTHESIS,
            attributes = mapOf("state" to "COMPETING"),
        )
        val delta = ThoughtGraphDelta.create(
            sourceKey = "attention-restart",
            sourceRevision = 1,
            nodeVersions = listOf(goal, evidence, hypothesis),
            edgeVersions = listOf(
                edge(evidence, goal, ThoughtGraphEdgeKind.DERIVED_FROM, "durable-goal-source"),
                edge(evidence, hypothesis, ThoughtGraphEdgeKind.CONTRADICTS, "durable-contradiction"),
            ),
            observedAt = baseTime,
        )
        val repository = MemoryDeltaRepository()
        val firstGraph = DurableThoughtGraph(repository)
        firstGraph.append(delta, baseTime)
        val revisionBeforeAttention = firstGraph.state.value.revision
        val first = firstGraph.workingSet(asOf = baseTime, policy = ThoughtGraphAttentionPolicy(maxNodes = 8))

        assertEquals(revisionBeforeAttention, firstGraph.state.value.revision)

        val restarted = DurableThoughtGraph(repository)
        restarted.rehydrate(baseTime)
        val second = restarted.workingSet(asOf = baseTime, policy = ThoughtGraphAttentionPolicy(maxNodes = 8))

        assertEquals(first, second)
        assertEquals(first.fingerprint, second.fingerprint)
        assertEquals(revisionBeforeAttention, restarted.state.value.revision)
    }

    private fun snapshot(
        nodes: List<ThoughtGraphNodeVersion>,
        edges: List<ThoughtGraphEdgeVersion> = emptyList(),
        conflicts: List<ThoughtGraphConflict> = emptyList(),
    ): ThoughtGraphSnapshot = ThoughtGraphSnapshot(
        revision = 0,
        activeNodes = nodes.sortedBy { it.id.value },
        activeEdges = edges.sortedBy { it.id.value },
        conflicts = conflicts.sortedWith(conflictOrdering()),
        nodeHistoryCount = nodes.size,
        edgeHistoryCount = edges.size,
        appliedDeltaIds = emptyList(),
        capturedAt = baseTime,
        historyFingerprint = "history:${nodes.size}:${edges.size}:${conflicts.size}",
    )

    private fun node(
        id: String,
        kind: ThoughtGraphNodeKind,
        confidence: Double = 0.70,
        authority: Double = 0.60,
        validity: TemporalValidity = TemporalValidity.UNBOUNDED,
        attributes: Map<String, String> = emptyMap(),
    ): ThoughtGraphNodeVersion {
        val provenance = ThoughtGraphProvenance(
            sourceKind = when (kind) {
                ThoughtGraphNodeKind.PHOTON -> ThoughtGraphSourceKind.PHOTON
                ThoughtGraphNodeKind.EVIDENCE -> ThoughtGraphSourceKind.EVIDENCE
                ThoughtGraphNodeKind.HYPOTHESIS -> ThoughtGraphSourceKind.HYPOTHESIS
                ThoughtGraphNodeKind.GOAL -> ThoughtGraphSourceKind.GOAL
                ThoughtGraphNodeKind.CONFLICT -> ThoughtGraphSourceKind.SYSTEM
            },
            sourceId = id,
            sourceRevision = 1,
            sourceFingerprint = "fingerprint:$id",
            origin = "attention-test",
            actor = "test",
            createdAt = baseTime,
        )
        return ThoughtGraphNodeVersion.create(
            kind = kind,
            semanticKey = id,
            summary = "summary $id",
            confidence = confidence,
            authority = authority,
            validity = validity,
            provenance = provenance,
            attributes = attributes,
        )
    }

    private fun edge(
        source: ThoughtGraphNodeVersion,
        target: ThoughtGraphNodeVersion,
        kind: ThoughtGraphEdgeKind,
        semanticKey: String,
    ): ThoughtGraphEdgeVersion = ThoughtGraphEdgeVersion.create(
        sourceNodeId = source.id,
        targetNodeId = target.id,
        kind = kind,
        semanticKey = semanticKey,
        confidence = 0.80,
        authority = 0.75,
        validity = TemporalValidity.UNBOUNDED,
        provenance = source.provenance,
        explanation = "attention test edge $semanticKey",
    )

    private class MemoryDeltaRepository : ThoughtGraphDeltaRepository {
        private val values = linkedMapOf<ThoughtGraphDeltaId, ThoughtGraphDelta>()

        override suspend fun save(delta: ThoughtGraphDelta): ThoughtGraphDeltaWriteResult {
            val existing = values[delta.id]
            if (existing != null) return ThoughtGraphDeltaWriteResult.Duplicate(existing)
            values[delta.id] = delta
            return ThoughtGraphDeltaWriteResult.Stored(delta)
        }

        override suspend fun load(id: ThoughtGraphDeltaId): ThoughtGraphDelta? = values[id]

        override suspend fun loadReport(): ThoughtGraphDeltaLoadReport = ThoughtGraphDeltaLoadReport(
            deltas = values.values.sortedBy { it.id.value },
            unreadableEntries = emptyList(),
        )
    }
}
