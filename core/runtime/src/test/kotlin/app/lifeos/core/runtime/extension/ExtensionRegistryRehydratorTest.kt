package app.lifeos.core.runtime.extension

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtensionRegistryRehydratorTest {
    @Test
    fun rehydratesExactHeadSnapshotWithoutHistoryScan() = runTest {
        val snapshot = snapshot()
        val head = ExtensionRegistryHead.create(
            revision = 7,
            snapshot = snapshot,
            predecessorSnapshotId = "extension-registry:previous",
        )
        val snapshots = RecordingSnapshotRepository(mapOf(snapshot.id to snapshot))
        val report = ExtensionRegistryRehydrator(
            heads = StaticHeadRepository(head),
            snapshots = snapshots,
        ).rehydrate()

        assertEquals(head, report.head)
        assertEquals(snapshot, report.snapshot)
        assertEquals(snapshot.entries.size, report.restoredEntries)
        assertEquals(listOf(snapshot.id), snapshots.requestedIds)
        assertTrue(snapshots.loadAllWasCalled.not())
    }

    @Test
    fun emptyHeadRestoresNothing() = runTest {
        val snapshots = RecordingSnapshotRepository(emptyMap())
        val report = ExtensionRegistryRehydrator(
            heads = StaticHeadRepository(null),
            snapshots = snapshots,
        ).rehydrate()

        assertNull(report.head)
        assertNull(report.snapshot)
        assertEquals(0, report.restoredEntries)
        assertTrue(snapshots.requestedIds.isEmpty())
    }

    @Test
    fun missingHeadSnapshotFailsClosed() = runTest {
        val snapshot = snapshot()
        val head = ExtensionRegistryHead.create(
            revision = 1,
            snapshot = snapshot,
            predecessorSnapshotId = null,
        )

        assertFailsWith<IllegalArgumentException> {
            ExtensionRegistryRehydrator(
                heads = StaticHeadRepository(head),
                snapshots = RecordingSnapshotRepository(emptyMap()),
            ).rehydrate()
        }
    }

    @Test
    fun tamperedHeadFingerprintFailsClosedAtRestore() {
        val snapshot = snapshot()

        assertFailsWith<IllegalArgumentException> {
            ExtensionRegistryHead.restore(
                revision = 1,
                activeSnapshotId = snapshot.id,
                predecessorSnapshotId = null,
                snapshotFingerprint = snapshot.fingerprint(),
                fingerprint = "tampered",
            )
        }
    }

    private fun snapshot(): ExtensionRegistrySnapshot {
        val manifest = ExtensionManifest(
            extensionId = ExtensionId("extension.signal"),
            version = ExtensionVersion("1.0.0"),
            kind = ExtensionKind.WORLD_SIGNAL_PACK,
            providerId = "lifeos.test",
            entrypoints = setOf(
                ExtensionEntrypoint("world.signal.projector", "projector.test"),
            ),
        )
        return ExtensionRegistrySnapshot.create(
            listOf(
                ExtensionRegistryEntry(
                    manifest = manifest,
                    worldContract = ExtensionWorldContract(
                        worldSignalSchemaVersion = WorldSignalSchemaVersion(1, 0),
                        worldNodeSchemaVersion = WorldNodeSchemaVersion(1, 0),
                        worldEquationVersion = WorldEquationVersion(
                            id = "lifeos-world-informational-v1",
                            major = 1,
                            minor = 0,
                        ),
                        coefficientSchemaFingerprint = CoefficientSchemaFingerprint("coeff-v1"),
                        projectionContractFingerprint = ProjectionContractFingerprint("projection-v1"),
                    ),
                ),
            ),
        )
    }

    private class StaticHeadRepository(
        private val head: ExtensionRegistryHead?,
    ) : ExtensionRegistryHeadRepository {
        override suspend fun load(): ExtensionRegistryHead? = head

        override suspend fun compareAndSet(
            expectedRevision: Long?,
            next: ExtensionRegistryHead,
        ): Boolean = error("not used")
    }

    private class RecordingSnapshotRepository(
        private val byId: Map<String, ExtensionRegistrySnapshot>,
    ) : ExtensionRegistrySnapshotRepository {
        val requestedIds = mutableListOf<String>()
        var loadAllWasCalled = false
            private set

        override suspend fun save(snapshot: ExtensionRegistrySnapshot) {
            error("not used")
        }

        override suspend fun load(id: String): ExtensionRegistrySnapshot? {
            requestedIds += id
            return byId[id]
        }
    }
}
