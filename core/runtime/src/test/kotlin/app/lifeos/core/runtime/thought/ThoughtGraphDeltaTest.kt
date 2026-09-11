package app.lifeos.core.runtime.thought

import app.lifeos.core.field.TemporalValidity
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ThoughtGraphDeltaTest {
    private val t0 = Instant.parse("2026-09-11T06:00:00Z")
    private val t1 = t0.plusSeconds(60)
    private val t2 = t1.plusSeconds(60)

    @Test
    fun deltaIdentityAndOrderingAreIndependentOfInputOrder() {
        val a = node(sourceId = "photon-a", revision = 1, semanticKey = "a", summary = "alpha")
        val b = node(sourceId = "photon-b", revision = 1, semanticKey = "b", summary = "beta")
        val edge = edge(a, b, revision = 1)

        val forward = ThoughtGraphDelta.create(
            sourceKey = "photon-batch",
            sourceRevision = 1,
            nodeVersions = listOf(a, b),
            edgeVersions = listOf(edge),
            observedAt = t0,
        )
        val reversed = ThoughtGraphDelta.create(
            sourceKey = "photon-batch",
            sourceRevision = 1,
            nodeVersions = listOf(b, a),
            edgeVersions = listOf(edge),
            observedAt = t2,
        )

        assertEquals(forward.id, reversed.id)
        assertEquals(forward.nodeVersions, reversed.nodeVersions)
        assertEquals(forward.edgeVersions, reversed.edgeVersions)
        assertEquals(listOf(a, b).sortedWith(nodeVersionOrdering()), forward.nodeVersions)
    }

    @Test
    fun exactDeltaReplayIsIdempotentAndDoesNotAdvanceRevision() {
        val reducer = ThoughtGraphReducer { t2 }
        val version = node("photon-replay", 1, "topic", "first")
        val delta = delta("replay-source", 1, version)

        val first = reducer.apply(ThoughtGraphState(), delta, t1)
        val replay = reducer.apply(first.state, delta, t2)

        assertFalse(first.replayed)
        assertTrue(replay.replayed)
        assertEquals(1L, replay.state.revision)
        assertEquals(1, replay.state.nodeVersions.size)
        assertEquals(first.snapshot.contentFingerprint, replay.snapshot.contentFingerprint)
        assertEquals(first.snapshot.historyFingerprint, replay.snapshot.historyFingerprint)
    }

    @Test
    fun staleVersionStaysInHistoryButCannotReplaceNewerActiveVersion() {
        val reducer = ThoughtGraphReducer { t2 }
        val older = node("photon-stale", 1, "topic", "old")
        val newer = node("photon-stale", 2, "topic", "new")

        val afterNewer = reducer.apply(
            ThoughtGraphState(),
            delta("source-new", 2, newer),
            t1,
        )
        val afterOlder = reducer.apply(
            afterNewer.state,
            delta("source-old", 1, older),
            t2,
        )

        assertEquals(listOf(older.fingerprint), afterOlder.staleNodeVersionFingerprints)
        assertEquals(2, afterOlder.state.nodeVersions.size)
        assertEquals(2, afterOlder.snapshot.nodeHistoryCount)
        assertEquals(newer.fingerprint, afterOlder.snapshot.activeNodes.single().fingerprint)
        assertTrue(afterOlder.snapshot.conflicts.isEmpty())
    }

    @Test
    fun equalHighestRevisionDisagreementBecomesFirstClassNodeConflict() {
        val reducer = ThoughtGraphReducer { t2 }
        val left = node("photon-conflict", 4, "claim", "left")
        val right = node("photon-conflict", 4, "claim", "right", sourceFingerprint = "variant-right")
        assertEquals(left.id, right.id)
        assertNotEquals(left.fingerprint, right.fingerprint)

        val first = reducer.apply(ThoughtGraphState(), delta("left", 4, left), t1)
        val second = reducer.apply(first.state, delta("right", 4, right), t2)

        assertTrue(second.snapshot.activeNodes.isEmpty())
        val conflict = second.snapshot.conflicts.single()
        assertEquals(ThoughtGraphConflictSubjectKind.NODE, conflict.subjectKind)
        assertEquals(left.id.value, conflict.subjectId)
        assertEquals(4L, conflict.sourceRevision)
        assertEquals(listOf(left.fingerprint, right.fingerprint).sorted(), conflict.variantFingerprints)
    }

    @Test
    fun laterRevisionResolvesPriorConflictWithoutErasingHistory() {
        val reducer = ThoughtGraphReducer { t2 }
        val left = node("photon-resolve", 2, "claim", "left")
        val right = node("photon-resolve", 2, "claim", "right", sourceFingerprint = "right")
        val resolved = node("photon-resolve", 3, "claim", "resolved", sourceFingerprint = "resolution")

        var state = reducer.apply(ThoughtGraphState(), delta("left", 2, left), t0).state
        state = reducer.apply(state, delta("right", 2, right), t1).state
        val final = reducer.apply(state, delta("resolved", 3, resolved), t2)

        assertEquals(3, final.state.nodeVersions.size)
        assertEquals(resolved.fingerprint, final.snapshot.activeNodes.single().fingerprint)
        assertTrue(final.snapshot.conflicts.isEmpty())
    }

    @Test
    fun exactVersionInDistinctDeltaDoesNotManufactureConflict() {
        val reducer = ThoughtGraphReducer { t2 }
        val version = node("photon-shared", 1, "topic", "same")
        val first = reducer.apply(
            ThoughtGraphState(),
            delta("ingest-a", 1, version),
            t0,
        )
        val second = reducer.apply(
            first.state,
            delta("ingest-b", 1, version),
            t1,
        )

        assertEquals(2L, second.state.revision)
        assertEquals(1, second.state.nodeVersions.size)
        assertEquals(version.fingerprint, second.snapshot.activeNodes.single().fingerprint)
        assertTrue(second.snapshot.conflicts.isEmpty())
    }

    @Test
    fun finalSnapshotIsDeterministicAcrossOutOfOrderDeltaApplication() {
        val reducer = ThoughtGraphReducer { t2 }
        val oldA = node("photon-order", 1, "topic", "old")
        val newA = node("photon-order", 3, "topic", "new")
        val b = node("photon-b", 1, "b", "beta")
        val edge = edge(newA, b, revision = 3)
        val d1 = delta("old", 1, oldA)
        val d2 = ThoughtGraphDelta.create(
            sourceKey = "new",
            sourceRevision = 3,
            nodeVersions = listOf(newA, b),
            edgeVersions = listOf(edge),
            observedAt = t1,
        )

        val forward = reducer.apply(reducer.apply(ThoughtGraphState(), d1, t0).state, d2, t2)
        val reverse = reducer.apply(reducer.apply(ThoughtGraphState(), d2, t0).state, d1, t2)

        assertEquals(forward.state.nodeVersions, reverse.state.nodeVersions)
        assertEquals(forward.state.edgeVersions, reverse.state.edgeVersions)
        assertEquals(forward.state.appliedDeltaIds, reverse.state.appliedDeltaIds)
        assertEquals(forward.snapshot.historyFingerprint, reverse.snapshot.historyFingerprint)
        assertEquals(forward.snapshot.contentFingerprint, reverse.snapshot.contentFingerprint)
        assertEquals(forward.snapshot.activeNodes, reverse.snapshot.activeNodes)
        assertEquals(forward.snapshot.activeEdges, reverse.snapshot.activeEdges)
    }

    @Test
    fun edgeMetadataSurvivesProjectionAndEqualRevisionEdgeConflictIsExplicit() {
        val reducer = ThoughtGraphReducer { t2 }
        val a = node("photon-edge-a", 1, "a", "alpha")
        val b = node("photon-edge-b", 1, "b", "beta")
        val firstEdge = edge(a, b, revision = 7, confidence = 0.72, authority = 0.93)
        val competingEdge = edge(
            a,
            b,
            revision = 7,
            confidence = 0.31,
            authority = 0.55,
            sourceFingerprint = "edge-variant",
        )
        assertEquals(firstEdge.id, competingEdge.id)
        assertNotEquals(firstEdge.fingerprint, competingEdge.fingerprint)

        val base = ThoughtGraphDelta.create(
            sourceKey = "edge-base",
            sourceRevision = 7,
            nodeVersions = listOf(a, b),
            edgeVersions = listOf(firstEdge),
            observedAt = t0,
        )
        val first = reducer.apply(ThoughtGraphState(), base, t0)
        assertEquals(0.72, first.snapshot.activeEdges.single().confidence)
        assertEquals(0.93, first.snapshot.activeEdges.single().authority)
        assertEquals(t0, first.snapshot.activeEdges.single().validity.validFrom)
        assertEquals(t2, first.snapshot.activeEdges.single().validity.validUntilExclusive)

        val conflictDelta = ThoughtGraphDelta.create(
            sourceKey = "edge-variant",
            sourceRevision = 7,
            edgeVersions = listOf(competingEdge),
            observedAt = t1,
        )
        val conflicted = reducer.apply(first.state, conflictDelta, t2)

        assertTrue(conflicted.snapshot.activeEdges.isEmpty())
        assertTrue(conflicted.snapshot.activeNodes.size == 2)
        val conflict = conflicted.snapshot.conflicts.single()
        assertEquals(ThoughtGraphConflictSubjectKind.EDGE, conflict.subjectKind)
        assertEquals(firstEdge.id.value, conflict.subjectId)
    }

    private fun delta(
        sourceKey: String,
        sourceRevision: Long,
        node: ThoughtGraphNodeVersion,
    ) = ThoughtGraphDelta.create(
        sourceKey = sourceKey,
        sourceRevision = sourceRevision,
        nodeVersions = listOf(node),
        observedAt = t0,
    )

    private fun node(
        sourceId: String,
        revision: Long,
        semanticKey: String,
        summary: String,
        sourceFingerprint: String = "source:$sourceId:r$revision:$summary",
    ): ThoughtGraphNodeVersion = ThoughtGraphNodeVersion.create(
        kind = ThoughtGraphNodeKind.PHOTON,
        semanticKey = semanticKey,
        summary = summary,
        confidence = 0.81,
        authority = 0.74,
        validity = TemporalValidity(t0, t2),
        provenance = ThoughtGraphProvenance(
            sourceKind = ThoughtGraphSourceKind.PHOTON,
            sourceId = sourceId,
            sourceRevision = revision,
            sourceFingerprint = sourceFingerprint,
            origin = "test",
            actor = "ThoughtGraphDeltaTest",
            createdAt = t0,
        ),
        attributes = mapOf("scope" to "test"),
    )

    private fun edge(
        source: ThoughtGraphNodeVersion,
        target: ThoughtGraphNodeVersion,
        revision: Long,
        confidence: Double = 0.88,
        authority: Double = 0.79,
        sourceFingerprint: String = "edge:${source.id.value}:${target.id.value}:r$revision",
    ): ThoughtGraphEdgeVersion = ThoughtGraphEdgeVersion.create(
        sourceNodeId = source.id,
        targetNodeId = target.id,
        kind = ThoughtGraphEdgeKind.SUPPORTS,
        semanticKey = "support",
        confidence = confidence,
        authority = authority,
        validity = TemporalValidity(t0, t2),
        provenance = ThoughtGraphProvenance(
            sourceKind = ThoughtGraphSourceKind.PHOTON,
            sourceId = source.provenance.sourceId,
            sourceRevision = revision,
            sourceFingerprint = sourceFingerprint,
            origin = "test-edge",
            actor = "ThoughtGraphDeltaTest",
            createdAt = t0,
        ),
        explanation = "test relation",
    )
}
