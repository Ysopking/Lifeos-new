package app.lifeos.core.runtime

import app.lifeos.core.field.world.WorldFieldState
import app.lifeos.core.runtime.world.WorldFormulaSnapshot
import app.lifeos.core.runtime.world.WorldFormulaSnapshotCodec
import app.lifeos.core.runtime.world.WorldFormulaSnapshotLoadReport
import app.lifeos.core.runtime.world.WorldFormulaSnapshotRepository
import app.lifeos.core.runtime.world.WorldFormulaStatus
import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.cognition.CognitiveEvent
import app.lifeos.core.runtime.cognition.InMemoryCognitiveEventJournal
import app.lifeos.core.runtime.cognition.PhotonDelta
import app.lifeos.core.runtime.cognition.PhotonDeltaType
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CognitiveSnapshotManagerTest {
    @Test
    fun replayPagesUntilFrozenJournalHead() = runTest {
        val repository = InMemorySnapshotRepository()
        val manager = CognitiveSnapshotManager(repository)
        val journal = InMemoryCognitiveEventJournal()
        val base = Instant.parse("2026-09-18T10:00:00Z")

        repeat(5_000) { index ->
            journal.append(
                CognitiveEvent(
                    eventId = "event-$index",
                    delta = PhotonDelta(
                        deltaId = "delta-$index",
                        source = "snapshot-test",
                        photonId = PhotonId("photon-$index"),
                        revisionAfter = 1L,
                        type = PhotonDeltaType.CREATED,
                        timestamp = base.plusSeconds(index.toLong()),
                    ),
                    recordedAt = base.plusSeconds(index.toLong()),
                )
            )
        }

        val replay = manager.replay(journal, tailLimit = 256)

        assertNull(replay.snapshot)
        assertEquals(5_000, replay.tail.size)
        assertEquals(1L, replay.tail.first().offset)
        assertEquals(5_000L, replay.tail.last().offset)
    }

    @Test
    fun snapshotAheadOfJournalHeadIsIgnored() = runTest {
        val future = CognitiveSnapshot(
            schemaVersion = 1,
            projectionVersion = 1,
            worldRevision = 7L,
            eventSequence = 10L,
            worldRoot = "world-root",
            dependencyIndexFingerprint = "dependencies",
            memoryIndexFingerprint = "memory",
            payload = byteArrayOf(1, 2, 3),
        )
        val repository = InMemorySnapshotRepository()
        val manager = CognitiveSnapshotManager(repository)
        manager.persist(future)
        val journal = InMemoryCognitiveEventJournal()

        val replay = manager.replay(journal, tailLimit = 16)

        assertNull(replay.snapshot)
        assertEquals(emptyList(), replay.tail)
    }

    @Test
    fun producerPersistsReconstructibleWorldPayloadWithLiveFingerprints() = runTest {
        val repository = InMemorySnapshotRepository()
        val manager = CognitiveSnapshotManager(repository)
        val journal = InMemoryCognitiveEventJournal()
        journal.append(
            CognitiveEvent(
                eventId = "checkpoint-event",
                delta = PhotonDelta(
                    deltaId = "checkpoint-delta",
                    source = "snapshot-test",
                    photonId = PhotonId("checkpoint-photon"),
                    revisionAfter = 1L,
                    type = PhotonDeltaType.CREATED,
                    timestamp = Instant.parse("2026-09-18T11:00:00Z"),
                ),
                recordedAt = Instant.parse("2026-09-18T11:00:00Z"),
            )
        )
        val world = worldSnapshot()
        val producer = CognitiveSnapshotProducer(
            manager = manager,
            journal = journal,
            worlds = InMemoryWorldRepository(world),
            activeWorldSnapshotId = { world.id },
            dependencyState = {
                CognitiveSnapshotDependencyState(
                    revision = 9L,
                    fingerprint = "thought-graph-fingerprint",
                )
            },
            memoryFingerprint = { "memory-fingerprint" },
        )

        val captured = assertNotNull(producer.captureLatest())

        assertEquals(9L, captured.worldRevision)
        assertEquals(1L, captured.eventSequence)
        assertEquals(world.id, captured.worldRoot)
        assertEquals("thought-graph-fingerprint", captured.dependencyIndexFingerprint)
        assertEquals("memory-fingerprint", captured.memoryIndexFingerprint)
        assertEquals(world, WorldFormulaSnapshotCodec.decode(captured.payload))
        assertEquals(captured, manager.latestVerified())
    }

    @Test
    fun schemaV2RejectsPayloadWhoseWorldRootDoesNotMatch() {
        val world = worldSnapshot()
        val snapshot = CognitiveSnapshot(
            schemaVersion = 2,
            projectionVersion = 2,
            worldRevision = 1L,
            eventSequence = 0L,
            worldRoot = "world-snapshot:not-the-payload-root",
            payload = WorldFormulaSnapshotCodec.encode(world),
            dependencyIndexFingerprint = "dependency",
            memoryIndexFingerprint = "memory",
        )
        val verifier = SnapshotVerifier()

        assertFalse(verifier.verify(snapshot, verifier.manifest(snapshot)))
    }

    private fun worldSnapshot(): WorldFormulaSnapshot {
        val state = WorldFieldState(
            graphFingerprint = "graph-fingerprint",
            equationFingerprint = "equation-fingerprint",
            generation = 1,
            vectors = emptyMap(),
        )
        return WorldFormulaSnapshot.create(
            runId = "world-run:snapshot-test",
            requestId = "world-request:snapshot-test",
            equationVersion = "equation-v1",
            equationFingerprint = state.equationFingerprint,
            graphFingerprint = state.graphFingerprint,
            configFingerprint = "config-fingerprint",
            status = WorldFormulaStatus.CONVERGED,
            finalState = state,
            iterations = emptyList(),
            conflicts = emptyList(),
            anomalies = emptyList(),
            inputSnapshotFingerprints = setOf("input-fingerprint"),
        )
    }

    private class InMemoryWorldRepository(
        private val world: WorldFormulaSnapshot,
    ) : WorldFormulaSnapshotRepository {
        override suspend fun save(snapshot: WorldFormulaSnapshot) = Unit
        override suspend fun load(id: String): WorldFormulaSnapshot? =
            world.takeIf { it.id == id }
        override suspend fun loadLatest(): WorldFormulaSnapshot = world
        override suspend fun loadReport(): WorldFormulaSnapshotLoadReport =
            WorldFormulaSnapshotLoadReport(listOf(world), emptyList())
        override suspend fun delete(id: String) = Unit
    }

    private class InMemorySnapshotRepository : CognitiveSnapshotRepository {
        private val values = mutableListOf<Pair<CognitiveSnapshot, SnapshotManifest>>()

        override suspend fun loadAll(): List<Pair<CognitiveSnapshot, SnapshotManifest>> =
            values.toList()

        override suspend fun save(
            snapshot: CognitiveSnapshot,
            manifest: SnapshotManifest,
        ) {
            values += snapshot to manifest
        }
    }
}
