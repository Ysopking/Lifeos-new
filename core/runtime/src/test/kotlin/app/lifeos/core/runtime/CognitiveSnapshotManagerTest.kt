package app.lifeos.core.runtime

import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.cognition.CognitiveEvent
import app.lifeos.core.runtime.cognition.InMemoryCognitiveEventJournal
import app.lifeos.core.runtime.cognition.PhotonDelta
import app.lifeos.core.runtime.cognition.PhotonDeltaType
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
