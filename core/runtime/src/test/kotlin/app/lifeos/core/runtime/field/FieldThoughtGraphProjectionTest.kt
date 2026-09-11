package app.lifeos.core.runtime.field

import app.lifeos.core.field.FieldConvergenceEngine
import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.FieldSnapshot
import app.lifeos.core.field.FieldSnapshotId
import app.lifeos.core.field.FieldSnapshotLoadReport
import app.lifeos.core.field.FieldSnapshotRepository
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.thought.DurableThoughtGraph
import app.lifeos.core.runtime.thought.ThoughtGraphDelta
import app.lifeos.core.runtime.thought.ThoughtGraphDeltaId
import app.lifeos.core.runtime.thought.ThoughtGraphDeltaLoadReport
import app.lifeos.core.runtime.thought.ThoughtGraphDeltaRepository
import app.lifeos.core.runtime.thought.ThoughtGraphDeltaWriteResult
import app.lifeos.core.runtime.thought.ThoughtGraphEdgeKind
import app.lifeos.core.runtime.thought.ThoughtGraphNodeKind
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class FieldThoughtGraphProjectionTest {
    private val fixedTime = Instant.parse("2026-09-11T08:00:00Z")

    @Test
    fun `codec round trip preserves exact snapshot bound delta identity`() {
        val fixture = fixture()
        val decoded = FieldThoughtGraphProjectionCodec.decode(
            FieldThoughtGraphProjectionCodec.encode(fixture.envelope),
        )

        assertEquals(fixture.envelope, decoded)
        assertEquals(fixture.result.snapshot.id, decoded.snapshotId)
        assertEquals(fixture.result.snapshot.contentFingerprint(), decoded.snapshotFingerprint)
    }

    @Test
    fun `support edge runs from evidence into hypothesis`() {
        val delta = fixture().envelope.delta
        val support = delta.edgeVersions.single { it.kind == ThoughtGraphEdgeKind.SUPPORTS }
        val source = delta.nodeVersions.single { it.id == support.sourceNodeId }
        val target = delta.nodeVersions.single { it.id == support.targetNodeId }

        assertEquals(ThoughtGraphNodeKind.EVIDENCE, source.kind)
        assertEquals(ThoughtGraphNodeKind.HYPOTHESIS, target.kind)
    }

    @Test
    fun `kill before snapshot leaves envelope deferred and graph untouched`() = runTest {
        val fixture = fixture()
        val outbox = MemoryOutbox()
        val snapshots = MemorySnapshots()
        val graph = DurableThoughtGraph(MemoryGraphDeltas())
        val coordinator = FieldThoughtGraphProjectionCoordinator(outbox, snapshots, graph)

        outbox.save(fixture.envelope)
        val report = coordinator.reconcile()

        assertEquals(0, report.projected)
        assertEquals(1, report.deferredWithoutSnapshot)
        assertEquals(0L, graph.state.value.revision)
    }

    @Test
    fun `kill after snapshot before graph append replays exact delta once`() = runTest {
        val fixture = fixture()
        val outbox = MemoryOutbox()
        val snapshots = MemorySnapshots()
        val graphDeltas = MemoryGraphDeltas()
        DurableThoughtGraph(graphDeltas)

        outbox.save(fixture.envelope)
        snapshots.save(fixture.result.snapshot)
        // Simulated process kill: no materialize call in the first process.

        val restartedGraph = DurableThoughtGraph(graphDeltas)
        restartedGraph.rehydrate(fixedTime)
        val restarted = FieldThoughtGraphProjectionCoordinator(outbox, snapshots, restartedGraph)
        val report = restarted.reconcile()

        assertEquals(1, report.projected)
        assertEquals(1L, restartedGraph.state.value.revision)
        assertTrue(fixture.envelope.delta.id in restartedGraph.state.value.appliedDeltaIds)
        assertEquals(1, graphDeltas.loadReport().deltas.size)

        val second = restarted.reconcile()
        assertEquals(0, second.projected)
        assertEquals(1, second.alreadyApplied)
        assertEquals(1L, restartedGraph.state.value.revision)
        assertEquals(1, graphDeltas.loadReport().deltas.size)
    }

    @Test
    fun `kill after graph append before outbox acknowledgement has no revision drift`() = runTest {
        val fixture = fixture()
        val outbox = MemoryOutbox()
        val snapshots = MemorySnapshots()
        val graphDeltas = MemoryGraphDeltas()
        val graph = DurableThoughtGraph(graphDeltas)
        val coordinator = FieldThoughtGraphProjectionCoordinator(outbox, snapshots, graph)

        outbox.save(fixture.envelope)
        snapshots.save(fixture.result.snapshot)
        assertTrue(coordinator.materialize(fixture.envelope))
        assertEquals(1L, graph.state.value.revision)

        val restartedGraph = DurableThoughtGraph(graphDeltas)
        restartedGraph.rehydrate(fixedTime.plusSeconds(1))
        val restarted = FieldThoughtGraphProjectionCoordinator(outbox, snapshots, restartedGraph)
        val report = restarted.reconcile()

        assertEquals(1, report.alreadyApplied)
        assertEquals(0, report.projected)
        assertEquals(1L, restartedGraph.state.value.revision)
        assertEquals(1, graphDeltas.loadReport().deltas.size)
    }

    @Test
    fun `corrupt outbox fails closed`() = runTest {
        val graph = DurableThoughtGraph(MemoryGraphDeltas())
        val coordinator = FieldThoughtGraphProjectionCoordinator(
            outbox = object : FieldThoughtGraphProjectionOutboxRepository {
                override suspend fun save(envelope: FieldThoughtGraphProjectionEnvelope) =
                    FieldThoughtGraphProjectionWriteResult.Stored(envelope)
                override suspend fun load(id: FieldThoughtGraphProjectionId) = null
                override suspend fun loadReport() = FieldThoughtGraphProjectionLoadReport(
                    envelopes = emptyList(),
                    unreadableEntries = listOf("corrupt.fgprojection"),
                )
            },
            snapshots = MemorySnapshots(),
            graph = graph,
        )

        assertFailsWith<IllegalArgumentException> { coordinator.reconcile() }
        assertEquals(0L, graph.state.value.revision)
    }

    @Test
    fun `snapshot mismatch cannot authorize graph truth`() = runTest {
        val fixture = fixture()
        val outbox = MemoryOutbox()
        val snapshots = MemorySnapshots()
        val graph = DurableThoughtGraph(MemoryGraphDeltas())
        val coordinator = FieldThoughtGraphProjectionCoordinator(outbox, snapshots, graph)
        val mismatched = FieldThoughtGraphProjectionEnvelope.create(
            snapshotId = fixture.result.snapshot.id,
            snapshotFingerprint = "mismatch",
            delta = fixture.envelope.delta,
        )

        outbox.save(mismatched)
        snapshots.save(fixture.result.snapshot)

        assertFailsWith<IllegalStateException> { coordinator.reconcile() }
        assertEquals(0L, graph.state.value.revision)
    }

    @Test
    fun `live adapter persists projection intent before snapshot and materializes after commit`() = runTest {
        val outbox = MemoryOutbox()
        val snapshots = MemorySnapshots()
        val graph = DurableThoughtGraph(MemoryGraphDeltas())
        val coordinator = FieldThoughtGraphProjectionCoordinator(outbox, snapshots, graph)
        val adapter = UniversalFieldRuntimeAdapter(
            snapshotRepository = snapshots,
            thoughtGraphProjection = coordinator,
        )

        val execution = adapter.process(photon())

        assertEquals(FieldShadowState.COMPLETED, execution.state)
        assertEquals(1, outbox.loadReport().envelopes.size)
        assertEquals(1, snapshots.snapshots.size)
        assertEquals(1L, graph.state.value.revision)
        assertEquals(1, graph.state.value.appliedDeltaIds.size)
    }

    private fun fixture(): Fixture {
        val photon = photon()
        val request = DefaultPhotonFieldRequestFactory().create(photon)
        val result = FieldConvergenceEngine().converge(request)
        val envelope = FieldThoughtGraphProjector().project(photon, request, result)
        return Fixture(result, envelope)
    }

    private fun photon(): Photon = Photon(
        id = PhotonId("projection-test-photon"),
        revision = 3,
        content = "durable field truth",
        semanticMass = 1.2,
        energy = 0.9,
        confidence = 0.92,
        provenance = Provenance(
            source = "projection-test",
            actor = "test",
            createdAt = fixedTime,
        ),
    )

    private data class Fixture(
        val result: app.lifeos.core.field.FieldConvergenceResult,
        val envelope: FieldThoughtGraphProjectionEnvelope,
    )

    private class MemoryOutbox : FieldThoughtGraphProjectionOutboxRepository {
        private val entries = linkedMapOf<FieldThoughtGraphProjectionId, FieldThoughtGraphProjectionEnvelope>()

        override suspend fun save(envelope: FieldThoughtGraphProjectionEnvelope): FieldThoughtGraphProjectionWriteResult {
            val existing = entries[envelope.id]
            if (existing != null) {
                require(existing == envelope)
                return FieldThoughtGraphProjectionWriteResult.Duplicate(existing)
            }
            entries[envelope.id] = envelope
            return FieldThoughtGraphProjectionWriteResult.Stored(envelope)
        }

        override suspend fun load(id: FieldThoughtGraphProjectionId) = entries[id]

        override suspend fun loadReport() = FieldThoughtGraphProjectionLoadReport(
            envelopes = entries.values.sortedBy { it.id.value },
            unreadableEntries = emptyList(),
        )
    }

    private class MemorySnapshots : FieldSnapshotRepository {
        val snapshots = linkedMapOf<FieldSnapshotId, FieldSnapshot>()

        override suspend fun save(snapshot: FieldSnapshot) {
            snapshots[snapshot.id] = snapshot
        }

        override suspend fun load(id: FieldSnapshotId) = snapshots[id]

        override suspend fun loadLatest(domainId: FieldDomainId) = snapshots.values
            .filter { it.domainId == domainId }
            .maxByOrNull { it.id.value }

        override suspend fun loadReport(domainId: FieldDomainId?) = FieldSnapshotLoadReport(
            snapshots = snapshots.values.filter { domainId == null || it.domainId == domainId },
            unreadableEntries = emptyList(),
        )

        override suspend fun delete(id: FieldSnapshotId) {
            snapshots.remove(id)
        }
    }

    private class MemoryGraphDeltas : ThoughtGraphDeltaRepository {
        private val deltas = linkedMapOf<ThoughtGraphDeltaId, ThoughtGraphDelta>()

        override suspend fun save(delta: ThoughtGraphDelta): ThoughtGraphDeltaWriteResult {
            val existing = deltas[delta.id]
            if (existing != null) {
                require(existing.copy(observedAt = delta.observedAt) == delta)
                return ThoughtGraphDeltaWriteResult.Duplicate(existing)
            }
            deltas[delta.id] = delta
            return ThoughtGraphDeltaWriteResult.Stored(delta)
        }

        override suspend fun load(id: ThoughtGraphDeltaId) = deltas[id]

        override suspend fun loadReport() = ThoughtGraphDeltaLoadReport(
            deltas = deltas.values.sortedBy { it.id.value },
            unreadableEntries = emptyList(),
        )
    }
}
