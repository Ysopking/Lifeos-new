package app.lifeos.core.data

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.livedata.LiveDataAccountKey
import app.lifeos.core.runtime.livedata.LiveDataAccountObservation
import app.lifeos.core.runtime.livedata.LiveDataCapability
import app.lifeos.core.runtime.livedata.LiveDataConnectorId
import app.lifeos.core.runtime.livedata.LiveDataDelta
import app.lifeos.core.runtime.livedata.LiveDataDeltaOperation
import app.lifeos.core.runtime.livedata.LiveDataIngestResult
import app.lifeos.core.runtime.livedata.LiveDataPermission
import app.lifeos.core.runtime.livedata.LiveDataPermissionState
import app.lifeos.core.runtime.livedata.LiveDataStreamKind
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class LiveSourceDeltaCoordinatorTest {
    private val at = Instant.parse("2026-09-19T02:30:00Z")
    private val sourceId = LiveSourceId("calendar-source")
    private val connectorId = LiveDataConnectorId("android-calendar")
    private val accountKey = LiveDataAccountKey("calendar-account")

    @Test
    fun permissionBlockIsObservedBeforeAnySourceRead() = runBlocking {
        val adapter = FakeAdapter(sourceId)
        val authority = FakeAuthority()
        val coordinator = LiveSourceDeltaCoordinator(
            connectors = listOf(
                connector(adapter, permissionState = LiveDataPermissionState.DENIED)
            ),
            cursors = MemoryCursorRepository(),
            hub = authority,
            now = { at },
        )

        val result = assertIs<LiveSourceSyncResult.PermissionBlocked>(coordinator.sync(sourceId))

        assertEquals(0, adapter.inventoryCalls)
        assertEquals(0, adapter.changeCalls)
        assertEquals(listOf("permission-not-granted:read_calendar"), result.reasons)
        assertEquals(1, authority.observations.size)
    }

    @Test
    fun bootstrapStoresOnlyOperationalCursorAndRepublishesItsFingerprintObservation() = runBlocking {
        val adapter = FakeAdapter(
            sourceId = sourceId,
            inventoryResult = SourceInventory(
                items = listOf(
                    SourceInventoryItem("event-b", "fb"),
                    SourceInventoryItem("event-a", "fa"),
                ),
                cursor = SourceCursor("opaque-cursor-1"),
            ),
        )
        val repository = MemoryCursorRepository()
        val authority = FakeAuthority()
        val coordinator = LiveSourceDeltaCoordinator(
            listOf(connector(adapter)),
            repository,
            authority,
            now = { at },
        )

        val result = assertIs<LiveSourceSyncResult.Bootstrapped>(coordinator.sync(sourceId))

        assertEquals(SourceCursor("opaque-cursor-1"), result.state.cursor)
        assertTrue(result.state.bootstrapped)
        assertTrue(result.state.baselineFingerprint?.matches(Regex("[0-9a-f]{64}")) == true)
        assertEquals(listOf(null, "opaque-cursor-1"), authority.observations.map { it.sourceCursor })
        assertTrue(authority.deltas.isEmpty())
    }

    @Test
    fun repeatedObjectChangesCoalesceAndCursorMovesAfterAcceptedDeltas() = runBlocking {
        val adapter = FakeAdapter(
            sourceId = sourceId,
            inventoryResult = SourceInventory(emptyList(), SourceCursor("cursor-1")),
            changeResult = SourceChangeSet(
                deltas = listOf(
                    delta("d1", "event-a", 1, "v1"),
                    delta("d2", "event-a", 2, "v2"),
                    delta("d3", "event-b", 3, "v1"),
                ),
                nextCursor = SourceCursor("cursor-2"),
            ),
        )
        val repository = MemoryCursorRepository()
        val authority = FakeAuthority()
        val coordinator = LiveSourceDeltaCoordinator(
            listOf(connector(adapter)),
            repository,
            authority,
            now = { at },
        )

        assertIs<LiveSourceSyncResult.Bootstrapped>(coordinator.sync(sourceId))
        val result = assertIs<LiveSourceSyncResult.Advanced>(coordinator.sync(sourceId))

        assertEquals(3, result.observedDeltaCount)
        assertEquals(2, result.coalescedDeltaCount)
        assertEquals(2, result.acceptedDeltaCount)
        assertEquals(listOf("event-a", "event-b"), authority.deltas.map { it.externalId })
        assertEquals(listOf("v2", "v1"), authority.deltas.map { it.externalVersion })
        assertEquals(SourceCursor("cursor-2"), result.state.cursor)
        assertEquals(3L, result.state.lastObservationRevision)
    }

    @Test
    fun blockedDeltaDoesNotAdvanceOperationalCursor() = runBlocking {
        val adapter = FakeAdapter(
            sourceId = sourceId,
            inventoryResult = SourceInventory(emptyList(), SourceCursor("cursor-1")),
            changeResult = SourceChangeSet(
                deltas = listOf(delta("d1", "event-a", 1, "v1")),
                nextCursor = SourceCursor("cursor-2"),
            ),
        )
        val repository = MemoryCursorRepository()
        val authority = FakeAuthority(
            ingestHandler = { LiveDataIngestResult.Blocked(listOf("permission-not-granted:read_calendar")) }
        )
        val coordinator = LiveSourceDeltaCoordinator(
            listOf(connector(adapter)),
            repository,
            authority,
            now = { at },
        )

        assertIs<LiveSourceSyncResult.Bootstrapped>(coordinator.sync(sourceId))
        val blocked = assertIs<LiveSourceSyncResult.DeltaBlocked>(coordinator.sync(sourceId))

        assertEquals("d1", blocked.deltaId)
        val durable = assertIs<LiveSourceCursorLoadResult.Loaded>(repository.load(sourceId)).state
        assertEquals(SourceCursor("cursor-1"), durable.cursor)
        assertEquals(0L, durable.lastObservationRevision)
    }

    @Test
    fun projectionFailureDoesNotAdvanceCursorOrAbortAsException() = runBlocking {
        val adapter = FakeAdapter(
            sourceId = sourceId,
            inventoryResult =
                SourceInventory(
                    emptyList(),
                    SourceCursor("cursor-1"),
                ),
            changeResult =
                SourceChangeSet(
                    deltas =
                        listOf(
                            delta(
                                "d1",
                                "event-a",
                                1,
                                "v1",
                            )
                        ),
                    nextCursor = SourceCursor("cursor-2"),
                ),
        )
        val repository = MemoryCursorRepository()
        val coordinator = LiveSourceDeltaCoordinator(
            listOf(
                connector(
                    adapter,
                    projectionFailure =
                        "projection-deferred",
                )
            ),
            repository,
            FakeAuthority(),
            now = { at },
        )

        assertIs<LiveSourceSyncResult.Bootstrapped>(
            coordinator.sync(sourceId)
        )
        val failed =
            assertIs<LiveSourceSyncResult.SourceUnavailable>(
                coordinator.sync(sourceId)
            )

        assertEquals("projection-deferred", failed.message)
        val durable =
            assertIs<LiveSourceCursorLoadResult.Loaded>(
                repository.load(sourceId)
            ).state
        assertEquals(
            SourceCursor("cursor-1"),
            durable.cursor,
        )
        assertEquals(0L, durable.lastObservationRevision)
    }

    @Test
    fun cursorlessSnapshotDiffSurvivesCoordinatorReconstruction() = runBlocking {
        val adapter = FakeAdapter(
            sourceId = sourceId,
            inventoryResult = SourceInventory(
                listOf(SourceInventoryItem("event-a", "v1")),
                null,
            ),
        )
        val cursors = MemoryCursorRepository()
        val snapshots = MemorySnapshotRepository()
        val authority = FakeAuthority()
        val first = LiveSourceDeltaCoordinator(
            connectors = listOf(connector(adapter)),
            cursors = cursors,
            hub = authority,
            snapshots = snapshots,
            now = { at },
        )

        val bootstrapped = assertIs<LiveSourceSyncResult.Bootstrapped>(first.sync(sourceId))
        assertEquals(null, bootstrapped.state.cursor)
        assertEquals(0L, bootstrapped.state.lastObservationRevision)

        adapter.inventoryResult = SourceInventory(
            listOf(
                SourceInventoryItem("event-b", "v1"),
                SourceInventoryItem("event-a", "v2"),
            ),
            null,
        )
        val reconstructed = LiveSourceDeltaCoordinator(
            connectors = listOf(connector(adapter)),
            cursors = cursors,
            hub = authority,
            snapshots = snapshots,
            now = { at.plusSeconds(1) },
        )

        val advanced = assertIs<LiveSourceSyncResult.Advanced>(reconstructed.sync(sourceId))

        assertEquals(2, advanced.observedDeltaCount)
        assertEquals(2, advanced.coalescedDeltaCount)
        assertEquals(2, advanced.acceptedDeltaCount)
        assertEquals(listOf("event-a", "event-b"), authority.deltas.map { it.externalId })
        assertEquals(2L, advanced.state.lastObservationRevision)
        assertEquals(null, advanced.state.cursor)

        val durableSnapshot = assertIs<LiveSourceSnapshotLoadResult.Loaded>(
            snapshots.load(sourceId)
        ).state
        assertEquals(2L, durableSnapshot.lastObservationRevision)
        assertEquals(listOf("event-a", "event-b"), durableSnapshot.items.map { it.externalKey })
        assertEquals(durableSnapshot.inventoryFingerprint, advanced.state.baselineFingerprint)
    }

    @Test
    fun snapshotAheadOfCursorIsReconciledAfterProcessDeathWithoutReplay() = runBlocking {
        val adapter = FakeAdapter(
            sourceId = sourceId,
            inventoryResult = SourceInventory(
                listOf(SourceInventoryItem("event-a", "v1")),
                null,
            ),
        )
        val cursors = MemoryCursorRepository()
        val snapshots = MemorySnapshotRepository()
        val authority = FakeAuthority()
        val first = LiveSourceDeltaCoordinator(
            connectors = listOf(connector(adapter)),
            cursors = cursors,
            hub = authority,
            snapshots = snapshots,
            now = { at },
        )
        assertIs<LiveSourceSyncResult.Bootstrapped>(first.sync(sourceId))

        val baseline = assertIs<LiveSourceSnapshotLoadResult.Loaded>(
            snapshots.load(sourceId)
        ).state
        val committedBeforeCrash = baseline.replace(
            nextItems = listOf(SourceInventoryItem("event-a", "v2")),
            nextObservationRevision = 1L,
            at = at.plusSeconds(1),
        )
        assertIs<LiveSourceSnapshotWriteResult.Saved>(
            snapshots.compareAndSet(sourceId, baseline.revision, committedBeforeCrash)
        )
        adapter.inventoryResult = SourceInventory(
            listOf(SourceInventoryItem("event-a", "v2")),
            null,
        )

        val reconstructed = LiveSourceDeltaCoordinator(
            connectors = listOf(connector(adapter)),
            cursors = cursors,
            hub = authority,
            snapshots = snapshots,
            now = { at.plusSeconds(2) },
        )
        val recovered = assertIs<LiveSourceSyncResult.Advanced>(reconstructed.sync(sourceId))

        assertEquals(0, recovered.observedDeltaCount)
        assertEquals(0, recovered.acceptedDeltaCount)
        assertEquals(1L, recovered.state.lastObservationRevision)
        assertEquals(committedBeforeCrash.inventoryFingerprint, recovered.state.baselineFingerprint)
        assertTrue(authority.deltas.isEmpty())
    }

    @Test
    fun changedAccountIdentityFailsClosedWithoutReadingSource() = runBlocking {
        val adapter = FakeAdapter(
            sourceId = sourceId,
            inventoryResult = SourceInventory(emptyList(), SourceCursor("cursor-1")),
        )
        val repository = MemoryCursorRepository()
        val authority = FakeAuthority()
        val first = LiveSourceDeltaCoordinator(
            listOf(connector(adapter)),
            repository,
            authority,
            now = { at },
        )
        assertIs<LiveSourceSyncResult.Bootstrapped>(first.sync(sourceId))
        val reads = adapter.inventoryCalls + adapter.changeCalls

        val second = LiveSourceDeltaCoordinator(
            listOf(connector(adapter, account = LiveDataAccountKey("different-account"))),
            repository,
            authority,
            now = { at.plusSeconds(1) },
        )

        assertIs<LiveSourceSyncResult.IdentityChanged>(second.sync(sourceId))
        assertEquals(reads, adapter.inventoryCalls + adapter.changeCalls)
    }

    private fun connector(
        adapter: FakeAdapter,
        permissionState: LiveDataPermissionState = LiveDataPermissionState.GRANTED,
        account: LiveDataAccountKey = accountKey,
        projectionFailure: String? = null,
    ): LiveSourceConnector = object : LiveSourceConnector {
        override val adapter: LiveSourceAdapter = adapter
        override val streamKind: LiveDataStreamKind = LiveDataStreamKind.CALENDAR
        override val connectorVersion: String = "android-calendar/v1"

        override suspend fun accountObservation(): LiveDataAccountObservation =
            LiveDataAccountObservation(
                connectorId = connectorId,
                accountKey = account,
                capabilities = setOf(LiveDataCapability.CALENDAR_DELTAS),
                permissions = mapOf(
                    LiveDataPermission.READ_MESSAGES to LiveDataPermissionState.UNAVAILABLE,
                    LiveDataPermission.READ_CALENDAR to permissionState,
                    LiveDataPermission.READ_FILES to LiveDataPermissionState.UNAVAILABLE,
                ),
                observedAt = at,
            )

        override suspend fun project(delta: SourceDelta): LiveDataDelta {
            projectionFailure?.let(::error)
            return LiveDataDelta(
                connectorId = connectorId,
                accountKey = account,
                kind = LiveDataStreamKind.CALENDAR,
                externalId = delta.externalKey,
                externalVersion = delta.newFingerprint ?: delta.deltaId,
                operation = if (delta.kind == SourceDeltaKind.DELETED) {
                    LiveDataDeltaOperation.DELETE
                } else {
                    LiveDataDeltaOperation.UPSERT
                },
                occurredAt = at,
                observedAt = at.plusSeconds(1),
                payload = if (delta.kind == SourceDeltaKind.DELETED) null else "delta=" + delta.deltaId,
            )
        }
    }

    private fun delta(
        id: String,
        key: String,
        revision: Long,
        version: String,
    ) = SourceDelta(
        deltaId = id,
        sourceId = sourceId,
        externalKey = key,
        kind = SourceDeltaKind.UPDATED,
        previousFingerprint = "previous-" + revision,
        newFingerprint = version,
        observationRevision = revision,
    )

    private class FakeAdapter(
        override val sourceId: LiveSourceId,
        var inventoryResult: SourceInventory = SourceInventory(emptyList(), null),
        private val changeResult: SourceChangeSet = SourceChangeSet(emptyList(), SourceCursor("next")),
    ) : LiveSourceAdapter {
        var inventoryCalls = 0
            private set
        var changeCalls = 0
            private set

        override suspend fun inventory(): SourceInventory {
            inventoryCalls += 1
            return inventoryResult
        }

        override suspend fun changesAfter(cursor: SourceCursor): SourceChangeSet {
            changeCalls += 1
            return changeResult
        }
    }

    private class MemoryCursorRepository : LiveSourceCursorRepository {
        private val states = linkedMapOf<LiveSourceId, LiveSourceCursorState>()

        override suspend fun load(sourceId: LiveSourceId): LiveSourceCursorLoadResult =
            states[sourceId]?.let(LiveSourceCursorLoadResult::Loaded)
                ?: LiveSourceCursorLoadResult.Missing

        override suspend fun compareAndSet(
            sourceId: LiveSourceId,
            expectedRevision: Long?,
            next: LiveSourceCursorState,
        ): LiveSourceCursorWriteResult {
            val current = states[sourceId]
            if (current?.revision != expectedRevision) {
                return LiveSourceCursorWriteResult.Conflict(current?.revision)
            }
            states[sourceId] = next
            return LiveSourceCursorWriteResult.Saved(next)
        }
    }

    private class MemorySnapshotRepository : LiveSourceSnapshotRepository {
        private val states = linkedMapOf<LiveSourceId, LiveSourceSnapshotState>()

        override suspend fun load(sourceId: LiveSourceId): LiveSourceSnapshotLoadResult =
            states[sourceId]?.let(LiveSourceSnapshotLoadResult::Loaded)
                ?: LiveSourceSnapshotLoadResult.Missing

        override suspend fun compareAndSet(
            sourceId: LiveSourceId,
            expectedRevision: Long?,
            next: LiveSourceSnapshotState,
        ): LiveSourceSnapshotWriteResult {
            val current = states[sourceId]
            if (current?.revision != expectedRevision) {
                return LiveSourceSnapshotWriteResult.Conflict(current?.revision)
            }
            states[sourceId] = next
            return LiveSourceSnapshotWriteResult.Saved(next)
        }
    }

    private class FakeAuthority(
        private val ingestHandler: suspend (LiveDataDelta) -> LiveDataIngestResult = {
            LiveDataIngestResult.Accepted(
                photonId = it.photonId,
                permissionSnapshotId = PhotonId("permission"),
                permissionSnapshotRevision = 1L,
            )
        },
    ) : LiveDataHubAuthority {
        val observations = mutableListOf<LiveDataAccountObservation>()
        val deltas = mutableListOf<LiveDataDelta>()

        override suspend fun observeAccount(observation: LiveDataAccountObservation): Photon {
            observations += observation
            return Photon(
                id = observation.photonId,
                content = "account",
                provenance = Provenance(
                    source = "test",
                    actor = "test",
                    createdAt = observation.observedAt,
                ),
            )
        }

        override suspend fun ingest(delta: LiveDataDelta): LiveDataIngestResult {
            deltas += delta
            return ingestHandler(delta)
        }
    }
}
