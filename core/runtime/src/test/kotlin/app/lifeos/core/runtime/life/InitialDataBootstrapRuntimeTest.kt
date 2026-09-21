package app.lifeos.core.runtime.life

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRepository
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class InitialDataBootstrapRuntimeTest {
    private val now = Instant.parse("2026-09-12T18:00:00Z")

    @Test
    fun allAvailableSourcesPageOnceAndReplayDoesNotRescan() = runTest {
        val repository = MemoryPhotonRepository()
        val contacts = MutableSource(
            descriptor = LifeSourceDescriptor("contacts", "contacts-v1"),
            records = records("contacts", 5),
        )
        val calendar = MutableSource(
            descriptor = LifeSourceDescriptor("calendar", "calendar-v1"),
            records = records("calendar", 3),
        )
        val runtime = runtime(repository, listOf(contacts, calendar), pageSize = 2)

        val first = runtime.run()
        val firstReadCount = contacts.readCount + calendar.readCount
        val second = runtime.run()

        assertEquals(InitialDataBootstrapStatus.COMPLETE, first.status)
        assertEquals(first.fingerprint, second.fingerprint)
        assertEquals(first.reportPhotonId, second.reportPhotonId)
        assertEquals(firstReadCount, contacts.readCount + calendar.readCount)
        assertEquals(8, first.sources.sumOf { it.durableRecordCount })
        assertEquals(1, repository.loadAll().count { "initial-data-bootstrap" in it.tags })
    }

    @Test
    fun unauthorizedSourceDoesNotAdvanceThenGrantAutomaticallyCompletes() = runTest {
        val repository = MemoryPhotonRepository()
        val contacts = MutableSource(
            descriptor = LifeSourceDescriptor("contacts", "contacts-v1"),
            records = records("contacts", 2),
            state = InitialDataSourceStatus.UNAUTHORIZED,
        )
        val runtime = runtime(repository, listOf(contacts))

        val denied = runtime.run()
        val deniedCheckpoint = PhotonBackedLifeSourceCheckpointStore(repository).load(contacts.descriptor)
        assertEquals(InitialDataBootstrapStatus.PARTIAL, denied.status)
        assertEquals(0L, deniedCheckpoint.revision)
        assertTrue(repository.loadAll().any { "permission-state:unauthorized" in it.tags })

        contacts.state = InitialDataSourceStatus.AVAILABLE
        val granted = runtime.run()
        val grantedCheckpoint = PhotonBackedLifeSourceCheckpointStore(repository).load(contacts.descriptor)

        assertEquals(InitialDataBootstrapStatus.COMPLETE, granted.status)
        assertNotEquals(denied.reportPhotonId, granted.reportPhotonId)
        assertTrue(grantedCheckpoint.revision > 0L)
        assertEquals(null, grantedCheckpoint.position)
        assertEquals(2, granted.sources.single().durableRecordCount)
    }

    @Test
    fun crashAfterEvidenceBeforeCheckpointResumesWithoutDuplicateRecords() = runTest {
        val backing = MemoryPhotonRepository()
        val repository = FailCheckpointOnceRepository(backing)
        val source = MutableSource(
            descriptor = LifeSourceDescriptor("calendar", "calendar-v1"),
            records = records("calendar", 4),
        )
        val runtime = runtime(repository, listOf(source), pageSize = 2)

        assertFailsWith<IllegalStateException> { runtime.run() }
        assertEquals(2, backing.loadAll().count { "life-source-evidence" in it.tags })

        repository.failCheckpoint = false
        val recovered = runtime.run()

        assertEquals(InitialDataBootstrapStatus.COMPLETE, recovered.status)
        assertEquals(4, backing.loadAll().count { "life-source-evidence" in it.tags })
        assertEquals(4, recovered.sources.single().durableRecordCount)
    }

    @Test
    fun adapterVersionChangeCreatesIndependentFirstReadIdentity() = runTest {
        val repository = MemoryPhotonRepository()
        val v1 = MutableSource(
            descriptor = LifeSourceDescriptor("contacts", "contacts-v1"),
            records = records("contacts", 1),
        )
        val first = runtime(repository, listOf(v1)).run()

        val v2 = MutableSource(
            descriptor = LifeSourceDescriptor("contacts", "contacts-v2"),
            records = records("contacts", 1),
        )
        val second = runtime(repository, listOf(v2)).run()

        assertNotEquals(first.sourceSetFingerprint, second.sourceSetFingerprint)
        assertNotEquals(first.reportPhotonId, second.reportPhotonId)
        assertEquals(2, repository.loadAll().count { "life-source-evidence" in it.tags })
    }

    @Test
    fun largeSourceStopsAtSliceBoundaryAndResumesExactCheckpoint() = runTest {
        val repository = MemoryPhotonRepository()
        val source = MutableSource(
            descriptor = LifeSourceDescriptor("media-sliced", "media-sliced-v1"),
            records = records("media-sliced", 900),
        )
        val runtime = runtime(repository, listOf(source), pageSize = 100)

        val first = runtime.run()
        val firstCheckpoint =
            PhotonBackedLifeSourceCheckpointStore(repository).load(source.descriptor)

        assertEquals(InitialDataBootstrapStatus.PARTIAL, first.status)
        assertTrue(first.continuationRequired)
        assertEquals(8, source.readCount)
        assertEquals("800", firstCheckpoint.position)
        assertEquals(800, first.sources.single().durableRecordCount)

        val second = runtime.run()
        val secondCheckpoint =
            PhotonBackedLifeSourceCheckpointStore(repository).load(source.descriptor)

        assertEquals(InitialDataBootstrapStatus.COMPLETE, second.status)
        assertTrue(!second.continuationRequired)
        assertEquals(9, source.readCount)
        assertEquals(null, secondCheckpoint.position)
        assertEquals(900, second.sources.single().durableRecordCount)
    }

    @Test
    fun largeSourceIsBoundedlyPagedAndCompleted() = runTest {
        val repository = MemoryPhotonRepository()
        val source = MutableSource(
            descriptor = LifeSourceDescriptor("media", "media-v1"),
            records = records("media", 625),
        )

        val result = runtime(repository, listOf(source), pageSize = 100).run()

        assertEquals(InitialDataBootstrapStatus.COMPLETE, result.status)
        assertEquals(625, result.sources.single().durableRecordCount)
        assertEquals(7, source.readCount)
    }

    @Test
    fun largeSourceYieldsAfterSliceBudgetAndResumesExactCheckpoint() = runTest {
        val repository = MemoryPhotonRepository()
        val source = MutableSource(
            descriptor = LifeSourceDescriptor("media", "media-v1"),
            records = records("media", 925),
        )
        val runtime = runtime(repository, listOf(source), pageSize = 100)

        val first = runtime.run()

        assertEquals(InitialDataBootstrapStatus.PARTIAL, first.status)
        assertTrue(first.continuationRequired)
        assertEquals(800, first.sources.single().durableRecordCount)
        assertEquals("800", first.sources.single().checkpointPosition)
        assertEquals(8, source.readCount)
        assertEquals(0, repository.loadAll().count { "initial-data-bootstrap" in it.tags })

        val second = runtime.run()

        assertEquals(InitialDataBootstrapStatus.COMPLETE, second.status)
        assertTrue(!second.continuationRequired)
        assertEquals(925, second.sources.single().durableRecordCount)
        assertEquals(null, second.sources.single().checkpointPosition)
        assertEquals(10, source.readCount)
        assertEquals(1, repository.loadAll().count { "initial-data-bootstrap" in it.tags })
    }

    private fun runtime(
        repository: PhotonRepository,
        sources: List<InitialDataSourceAdapter>,
        pageSize: Int = 100,
    ): InitialDataBootstrapRuntime = InitialDataBootstrapRuntime(
        photons = repository,
        memory = DurableLifeMemoryRuntime(repository),
        sources = sources,
        pageSize = pageSize,
        now = { now },
    )

    private fun records(sourceId: String, count: Int): List<LifeSourceRecord> = List(count) { index ->
        LifeSourceRecord(
            sourceId = sourceId,
            recordId = "record-${index + 1}",
            observedAt = now.plusSeconds(index.toLong()),
            payload = "$sourceId item ${index + 1}",
            tags = setOf("test-source"),
        )
    }

    private class MutableSource(
        override val descriptor: LifeSourceDescriptor,
        private val records: List<LifeSourceRecord>,
        var state: InitialDataSourceStatus = InitialDataSourceStatus.AVAILABLE,
    ) : InitialDataSourceAdapter {
        var readCount: Int = 0
            private set

        override suspend fun status(): InitialDataSourceStatus = state

        override suspend fun readPage(afterPosition: String?, limit: Int): InitialDataSourcePage {
            check(state == InitialDataSourceStatus.AVAILABLE)
            readCount += 1
            val start = afterPosition?.toInt() ?: 0
            val page = records.drop(start).take(limit)
            val consumed = start + page.size
            val complete = consumed >= records.size
            return InitialDataSourcePage(
                records = page,
                nextPosition = if (complete) null else consumed.toString(),
                complete = complete,
            )
        }
    }

    private open class MemoryPhotonRepository : PhotonRepository {
        protected val data = linkedMapOf<PhotonId, Photon>()
        override suspend fun save(photon: Photon) { data[photon.id] = photon }
        override suspend fun load(id: PhotonId): Photon? = data[id]
        override suspend fun loadReport(): PhotonLoadReport = PhotonLoadReport(data.values.toList(), emptyList())
        override suspend fun loadAll(): List<Photon> = data.values.toList()
        override suspend fun delete(id: PhotonId) { data.remove(id) }
    }

    private class FailCheckpointOnceRepository(
        private val delegate: MemoryPhotonRepository,
    ) : PhotonRepository {
        var failCheckpoint: Boolean = true
        override suspend fun save(photon: Photon) {
            if (failCheckpoint && "life-source-checkpoint" in photon.tags) {
                throw IllegalStateException("simulated checkpoint crash")
            }
            delegate.save(photon)
        }
        override suspend fun load(id: PhotonId): Photon? = delegate.load(id)
        override suspend fun loadReport(): PhotonLoadReport = delegate.loadReport()
        override suspend fun loadAll(): List<Photon> = delegate.loadAll()
        override suspend fun delete(id: PhotonId) = delegate.delete(id)
    }
}
