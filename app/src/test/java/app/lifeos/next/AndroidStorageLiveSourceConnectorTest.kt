package app.lifeos.next

import app.lifeos.core.data.SourceCursor
import app.lifeos.core.data.SourceDeltaKind
import app.lifeos.core.runtime.life.InitialDataSourceAdapter
import app.lifeos.core.runtime.life.InitialDataSourcePage
import app.lifeos.core.runtime.life.InitialDataSourceStatus
import app.lifeos.core.runtime.life.LifeSourceDescriptor
import app.lifeos.core.runtime.livedata.LiveDataDeltaOperation
import app.lifeos.core.runtime.livedata.LiveDataPermission
import app.lifeos.core.runtime.livedata.LiveDataPermissionState
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class AndroidStorageLiveSourceConnectorTest {
    private val at = Instant.parse("2026-09-21T11:00:00Z")

    @Test
    fun `storage journal starts at cursor zero and pages durable revisions`() = runBlocking {
        val journal = FakeJournal(
            changes = listOf(
                change(1L, "Documents/a.txt", SourceDeltaKind.CREATED),
                change(2L, "Documents/b.txt", SourceDeltaKind.UPDATED),
            )
        )
        val connector = connector(journal)

        assertEquals("0", connector.inventory().cursor?.value)
        assertEquals("0", connector.migrationCursor().value)

        val page = connector.changesAfter(SourceCursor("0"))
        assertEquals(listOf(1L, 2L), page.deltas.map { it.observationRevision })
        assertEquals("2", page.nextCursor.value)
    }

    @Test
    fun `available storage publishes actual bounded text content`() = runBlocking {
        val root = Files.createTempDirectory("lifeos-storage-live").toFile()
        try {
            val file = root.resolve("notes.txt")
            file.writeText("automatic first read works")
            val entry = entry(file, "Documents/notes.txt")
            val journal = FakeJournal(
                entries = mapOf(("primary" to entry.relativePath) to entry),
                changes = listOf(
                    change(
                        1L,
                        entry.relativePath,
                        SourceDeltaKind.CREATED,
                        newFingerprint = entry.metadataStateFingerprint,
                    )
                ),
            )
            val connector = connector(journal)

            val account = connector.accountObservation()
            assertEquals(
                LiveDataPermissionState.GRANTED,
                account.permissions[LiveDataPermission.READ_FILES],
            )

            val sourceDelta = connector.changesAfter(SourceCursor("0")).deltas.single()
            val projected = assertNotNull(connector.project(sourceDelta))

            assertEquals(LiveDataDeltaOperation.UPSERT, projected.operation)
            assertTrue(projected.payload.orEmpty().contains("decode_state=DECODED"))
            assertTrue(projected.payload.orEmpty().contains("automatic first read works"))
            assertTrue(projected.payload.orEmpty().toByteArray().size <= 512 * 1024)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `deleted storage event publishes delete without payload`() = runBlocking {
        val journal = FakeJournal(
            changes = listOf(
                change(7L, "Documents/gone.txt", SourceDeltaKind.DELETED)
            ),
        )
        val connector = connector(journal)
        val sourceDelta = connector.changesAfter(SourceCursor("0")).deltas.single()

        val projected = assertNotNull(connector.project(sourceDelta))

        assertEquals(LiveDataDeltaOperation.DELETE, projected.operation)
        assertNull(projected.payload)
    }

    @Test
    fun `denied broad files stays permission blocked before journal reads`() = runBlocking {
        val journal = FakeJournal()
        val connector = AndroidStorageLiveSourceConnector(
            inventory = journal,
            statusSource = FakeStatusSource(InitialDataSourceStatus.UNAUTHORIZED),
            now = { at },
        )

        val account = connector.accountObservation()

        assertEquals(
            LiveDataPermissionState.DENIED,
            account.permissions[LiveDataPermission.READ_FILES],
        )
        assertEquals(0, journal.changeReads)
    }

    private fun connector(
        journal: StorageChangeJournal,
    ): AndroidStorageLiveSourceConnector =
        AndroidStorageLiveSourceConnector(
            inventory = journal,
            statusSource = FakeStatusSource(InitialDataSourceStatus.AVAILABLE),
            now = { at },
        )

    private fun change(
        revision: Long,
        path: String,
        kind: SourceDeltaKind,
        newFingerprint: String? =
            if (kind == SourceDeltaKind.DELETED) null else "b".repeat(64),
    ): StorageChangeEntry =
        StorageChangeEntry(
            revision = revision,
            volumeId = "primary",
            relativePath = path,
            kind = kind,
            previousFingerprint =
                if (kind == SourceDeltaKind.CREATED) null else "a".repeat(64),
            newFingerprint = newFingerprint,
            observedAtMillis = at.toEpochMilli(),
        )

    private fun entry(
        file: java.io.File,
        path: String,
    ): StorageInventoryEntry =
        StorageInventoryEntry(
            volumeId = "primary",
            relativePath = path,
            absolutePath = file.absolutePath,
            sizeBytes = file.length(),
            modifiedAtMillis = file.lastModified().coerceAtLeast(0L),
            category = AndroidFileCategory.DOCUMENT,
            suspectedEncrypted = false,
            hashOffsetBytes = 0L,
            hashChain = null,
            contentFingerprint = null,
            lastSeenScanId = "scan-1",
        )

    private class FakeJournal(
        private val entries: Map<Pair<String, String>, StorageInventoryEntry> = emptyMap(),
        private val changes: List<StorageChangeEntry> = emptyList(),
    ) : StorageChangeJournal {
        var changeReads: Int = 0
            private set

        override fun load(
            volumeId: String,
            relativePath: String,
        ): StorageInventoryEntry? =
            entries[volumeId to relativePath]

        override fun loadChangesAfter(
            revisionExclusive: Long,
            limit: Int,
        ): List<StorageChangeEntry> {
            changeReads += 1
            return changes
                .filter { it.revision > revisionExclusive }
                .sortedBy { it.revision }
                .take(limit)
        }

        override fun currentChangeRevision(): Long =
            changes.maxOfOrNull { it.revision } ?: 0L

        override fun pruneChangesThrough(
            revisionInclusive: Long,
        ): Int = 0
    }

    private class FakeStatusSource(
        private val state: InitialDataSourceStatus,
    ) : InitialDataSourceAdapter {
        override val descriptor =
            LifeSourceDescriptor(
                AndroidSharedFilesInitialDataSource.SOURCE_ID,
                AndroidSharedFilesInitialDataSource.ADAPTER_VERSION,
            )

        override suspend fun status(): InitialDataSourceStatus =
            state

        override suspend fun readPage(
            afterPosition: String?,
            limit: Int,
        ): InitialDataSourcePage =
            error("Storage live connector must not page the legacy filesystem source")
    }
}
