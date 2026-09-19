package app.lifeos.core.runtime.boot

import app.lifeos.core.model.IncrementalPhotonIndexReader
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonIndexChange
import app.lifeos.core.model.PhotonIndexChangeOperation
import app.lifeos.core.model.PhotonIndexChanges
import app.lifeos.core.model.PhotonIndexEntry
import app.lifeos.core.model.PhotonIndexHead
import app.lifeos.core.model.PhotonIndexOrder
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.PhotonIndexReport
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.PhotonRevisionWriteResult
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RevisionedPhotonRepository
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IncrementalPhotonRepositoryBootSourceTest {
    private val at = Instant.parse("2026-09-19T18:00:00Z")

    @Test
    fun firstBootSnapshotsHeadsAndSecondBootConsumesOnlyJournalDeltaDiscovery() = runTest {
        val repository = FakeIncrementalRepository()
        val manifests = MemoryManifestRepository()
        val first = photon("a", 1, "a1")
        repository.installSnapshot(listOf(first))

        val source = IncrementalPhotonRepositoryBootSource(
            repository = repository,
            incrementalIndex = repository,
            manifests = manifests,
        )
        val firstLoad = source.load()
        assertEquals(listOf(first), firstLoad.photons)
        assertEquals(1, repository.indexReportCalls)

        val second = photon("b", 1, "b1")
        repository.create(second)
        val secondLoad = source.load()

        assertEquals(setOf(first.id, second.id), secondLoad.photons.map { it.id }.toSet())
        assertEquals(1, repository.indexReportCalls)
        assertEquals(repository.indexHead(), manifests.loadReport().manifest?.indexHead)
    }

    @Test
    fun advanceAndTombstoneAreAppliedAgainstExactPriorRefs() = runTest {
        val repository = FakeIncrementalRepository()
        val manifests = MemoryManifestRepository()
        val firstV1 = photon("a", 1, "a1")
        val second = photon("b", 1, "b1")
        repository.installSnapshot(listOf(firstV1, second))
        val source = IncrementalPhotonRepositoryBootSource(repository, repository, manifests)
        source.load()

        val firstV2 = photon("a", 2, "a2")
        repository.advance(firstV2)
        repository.tombstone(second.id)
        val load = source.load()

        assertEquals(listOf(firstV2), load.photons)
        val manifest = requireNotNull(manifests.loadReport().manifest)
        assertEquals(listOf(PhotonRevisionRef(firstV2.id, 2)), manifest.latestRefs)
    }

    @Test
    fun generationChangeRequiresSnapshotFallbackWithoutTrustingMissingJournalHistory() = runTest {
        val repository = FakeIncrementalRepository()
        val manifests = MemoryManifestRepository()
        val first = photon("a", 1, "a1")
        repository.installSnapshot(listOf(first))
        val source = IncrementalPhotonRepositoryBootSource(repository, repository, manifests)
        source.load()
        assertEquals(1, repository.indexReportCalls)

        repository.compact()
        val second = photon("b", 1, "b1")
        repository.create(second)
        val load = source.load()

        assertEquals(setOf(first.id, second.id), load.photons.map { it.id }.toSet())
        assertEquals(2, repository.indexReportCalls)
    }

    @Test
    fun unreadableExactRevisionDoesNotAdvanceManifest() = runTest {
        val repository = FakeIncrementalRepository()
        val manifests = MemoryManifestRepository()
        val first = photon("a", 1, "a1")
        repository.installSnapshot(listOf(first))
        val source = IncrementalPhotonRepositoryBootSource(repository, repository, manifests)
        source.load()
        val before = requireNotNull(manifests.loadReport().manifest)

        val second = photon("b", 1, "b1")
        repository.create(second)
        repository.unreadable += PhotonRevisionRef(second.id, second.revision)
        val load = source.load()

        assertTrue(load.unreadableFiles.any { it.contains("b@1") })
        assertEquals(before, manifests.loadReport().manifest)
    }

    private fun photon(id: String, revision: Long, content: String): Photon = Photon(
        id = PhotonId(id),
        revision = revision,
        content = content,
        phase = PhotonPhase.ACTIVE,
        provenance = Provenance("test", "m210", at.plusSeconds(revision)),
    )

    private class MemoryManifestRepository : IncrementalBootPhotonManifestRepository {
        var value: IncrementalBootPhotonManifest? = null

        override suspend fun loadReport(): IncrementalBootPhotonManifestLoadReport =
            IncrementalBootPhotonManifestLoadReport(value)

        override suspend fun compareAndSet(
            expectedFingerprint: String?,
            next: IncrementalBootPhotonManifest,
        ): Boolean {
            if (value?.fingerprint != expectedFingerprint) {
                if (!(value == null && expectedFingerprint == null)) return false
            }
            value = next
            return true
        }
    }

    private class FakeIncrementalRepository :
        RevisionedPhotonRepository,
        IncrementalPhotonIndexReader {
        private val values = linkedMapOf<PhotonRevisionRef, Photon>()
        private val current = linkedMapOf<PhotonId, PhotonRevisionRef>()
        private val journal = mutableListOf<PhotonIndexChange>()
        private var generation = 1L
        private var baseFingerprint = fingerprint("snapshot", generation.toString())
        private var sequence = 0L
        var indexReportCalls: Int = 0
            private set
        val unreadable = mutableSetOf<PhotonRevisionRef>()

        fun installSnapshot(photons: List<Photon>) {
            values.clear()
            current.clear()
            journal.clear()
            sequence = 0L
            photons.forEach { photon ->
                val ref = PhotonRevisionRef(photon.id, photon.revision)
                values[ref] = photon
                current[photon.id] = ref
            }
        }

        suspend fun create(photon: Photon) {
            val result = saveRevision(photon, expectedPreviousRevision = null)
            assertTrue(result is PhotonRevisionWriteResult.Created)
        }

        suspend fun advance(photon: Photon) {
            val previous = current[photon.id]
            val result = saveRevision(photon, expectedPreviousRevision = previous?.revision)
            assertTrue(result is PhotonRevisionWriteResult.Advanced)
        }

        fun tombstone(id: PhotonId) {
            val ref = requireNotNull(current.remove(id))
            sequence += 1L
            journal += PhotonIndexChange(
                sequence = sequence,
                operation = PhotonIndexChangeOperation.TOMBSTONE,
                ref = ref,
                previousHeadRef = ref,
                newEntry = entry(requireNotNull(values[ref]), tombstoned = true),
            )
        }

        fun compact() {
            generation += 1L
            baseFingerprint = fingerprint("snapshot", generation.toString(), sequence.toString())
            journal.clear()
        }

        override suspend fun indexHead(): PhotonIndexHead = PhotonIndexHead(
            snapshotGeneration = generation,
            snapshotFingerprint = baseFingerprint,
            lastJournalSequence = sequence,
        )

        override suspend fun changesSince(head: PhotonIndexHead): PhotonIndexChanges {
            val now = indexHead()
            if (
                head.snapshotGeneration != now.snapshotGeneration ||
                head.snapshotFingerprint != now.snapshotFingerprint
            ) {
                return PhotonIndexChanges.SnapshotRequired(now, "compacted")
            }
            val changes = journal.filter { it.sequence > head.lastJournalSequence }
            return PhotonIndexChanges.Incremental(head, now, changes)
        }

        override suspend fun save(photon: Photon) {
            val previous = current[photon.id]
            when (val result = saveRevision(photon, previous?.revision)) {
                is PhotonRevisionWriteResult.Created,
                is PhotonRevisionWriteResult.Advanced,
                is PhotonRevisionWriteResult.Idempotent -> Unit
                is PhotonRevisionWriteResult.Conflict -> error(result.reason)
            }
        }

        override suspend fun saveRevision(
            photon: Photon,
            expectedPreviousRevision: Long?,
        ): PhotonRevisionWriteResult {
            val previousRef = current[photon.id]
            val previous = previousRef?.let(values::get)
            if (previous == null) {
                if (expectedPreviousRevision != null || photon.revision != 1L) {
                    return PhotonRevisionWriteResult.Conflict(photon, null, "invalid-create")
                }
                val ref = PhotonRevisionRef(photon.id, photon.revision)
                values[ref] = photon
                current[photon.id] = ref
                sequence += 1L
                journal += PhotonIndexChange(
                    sequence = sequence,
                    operation = PhotonIndexChangeOperation.CREATE,
                    ref = ref,
                    previousHeadRef = null,
                    newEntry = entry(photon),
                )
                return PhotonRevisionWriteResult.Created(photon)
            }
            if (previous.revision == photon.revision) {
                return if (previous == photon) {
                    PhotonRevisionWriteResult.Idempotent(photon, previous)
                } else {
                    PhotonRevisionWriteResult.Conflict(photon, previous, "same-revision-differs")
                }
            }
            if (
                expectedPreviousRevision != previous.revision ||
                photon.revision != previous.revision + 1L
            ) {
                return PhotonRevisionWriteResult.Conflict(photon, previous, "cas-mismatch")
            }
            val ref = PhotonRevisionRef(photon.id, photon.revision)
            values[ref] = photon
            current[photon.id] = ref
            sequence += 1L
            journal += PhotonIndexChange(
                sequence = sequence,
                operation = PhotonIndexChangeOperation.ADVANCE,
                ref = ref,
                previousHeadRef = previousRef,
                newEntry = entry(photon),
            )
            return PhotonRevisionWriteResult.Advanced(photon, previous)
        }

        override suspend fun load(id: PhotonId): Photon? =
            current[id]?.let { load(it) }

        override suspend fun load(ref: PhotonRevisionRef): Photon? =
            if (ref in unreadable) throw IllegalStateException("simulated unreadable") else values[ref]

        override suspend fun latestRef(id: PhotonId): PhotonRevisionRef? = current[id]

        override suspend fun query(query: PhotonIndexQuery): List<PhotonRevisionRef> {
            val refs = current.values.sortedWith(
                compareBy<PhotonRevisionRef> { it.photonId.value }.thenBy { it.revision }
            )
            val start = query.after?.let { cursor ->
                refs.indexOf(cursor.lastRef).also { require(it >= 0) } + 1
            } ?: 0
            return refs.drop(start).take(query.limit)
        }

        override suspend fun indexReport(): PhotonIndexReport {
            indexReportCalls += 1
            return PhotonIndexReport(
                formatVersion = 2,
                entryCount = values.size,
                livePhotonCount = current.size,
                tombstonedPhotonCount = 0,
                latestRefs = current.toMap(),
            )
        }

        override suspend fun loadAll(): List<Photon> =
            current.values.mapNotNull(values::get)

        override suspend fun loadReport(): PhotonLoadReport =
            PhotonLoadReport(loadAll(), emptyList())

        override suspend fun delete(id: PhotonId) {
            tombstone(id)
        }

        private fun entry(
            photon: Photon,
            tombstoned: Boolean = false,
        ): PhotonIndexEntry = PhotonIndexEntry(
            ref = PhotonRevisionRef(photon.id, photon.revision),
            createdAt = photon.provenance.createdAt,
            phase = photon.phase,
            mimeType = photon.mimeType,
            tags = photon.tags,
            semanticMass = photon.semanticMass,
            confidence = photon.confidence,
            contentFingerprint = fingerprint(photon.content),
            latest = true,
            tombstoned = tombstoned,
        )

        private fun fingerprint(vararg parts: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(parts.joinToString("\u0000").encodeToByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
