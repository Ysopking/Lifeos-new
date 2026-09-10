package app.lifeos.core.runtime.boot

import app.lifeos.core.field.FieldSnapshotLoadReport
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.checkpoint.CheckpointLoadReport
import app.lifeos.core.model.task.TaskLoadReport
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BootCognitionReportTest {
    private val t0 = Instant.parse("2026-09-10T07:00:00Z")
    private val t1 = Instant.parse("2026-09-10T07:01:00Z")
    private val generation = BootGenerationId("a".repeat(64))

    @Test
    fun codecRoundTripsAndFingerprintIsStable() {
        val report = BootCognitionReport(
            currentGenerationId = generation,
            previousGenerationId = BootGenerationId("b".repeat(64)),
            disposition = BootCognitionDisposition.DEGRADED,
            snapshotPartial = true,
            sourcePhotonIds = listOf(PhotonId("p-a"), PhotonId("p-b")),
            evidence = listOf(
                BootCognitionEvidence(
                    BootCognitionEvidenceKind.INTEGRITY,
                    "SOURCE_ENTRY_UNREADABLE:broken.photon",
                    "severity=ERROR;area=SOURCE;message=unreadable",
                ),
                BootCognitionEvidence(
                    BootCognitionEvidenceKind.SNAPSHOT,
                    "counts",
                    "photons=2;tasks=0",
                ),
            ).sortedWith(compareBy({ it.kind.name }, { it.key }, { it.value })),
        )

        val encoded = BootCognitionReportCodec.encode(report)
        val decoded = BootCognitionReportCodec.decode(encoded)

        assertEquals(report, decoded)
        assertEquals(report.fingerprint(), decoded.fingerprint())
        assertEquals(encoded, BootCognitionReportCodec.encode(decoded))
    }

    @Test
    fun factoryProjectsSnapshotIntegrityContextAndDeltaIntoCanonicalEvidence() {
        val source = photon(
            id = "chat-1",
            content = "weiter",
            tags = setOf("context:conversation:conv-1", "context:active"),
        )
        val projection = BootContextProjection(
            kind = BootContextKind.CONVERSATION,
            contextId = "conv-1",
            sourcePhotonId = source.id,
            sourcePhotonRevision = source.revision,
            sourceCreatedAt = t0,
            explicitlyActive = true,
        )
        val snapshot = snapshot(
            photons = listOf(source),
            contexts = listOf(projection),
            failures = listOf(
                BootSnapshotReadFailure(BootSnapshotSource.PHOTON, "broken.photon", "unreadable-entry")
            ),
        )
        val integrity = BootIntegrityScanner(now = { t1 }).scan(snapshot)
        val context = BootContextRehydrator().rehydrate(snapshot)
        val delta = BootDeltaAnalyzer().analyze(previous = null, current = snapshot)

        val report = BootCognitionReportFactory().create(snapshot, integrity, context, delta)

        assertEquals(BootCognitionDisposition.RECOVERY_REQUIRED, report.disposition)
        assertTrue(report.snapshotPartial)
        assertEquals(listOf(PhotonId("chat-1")), report.sourcePhotonIds)
        assertTrue(report.evidence.any { it.kind == BootCognitionEvidenceKind.SNAPSHOT })
        assertTrue(report.evidence.any { it.kind == BootCognitionEvidenceKind.INTEGRITY })
        assertTrue(report.evidence.any { it.kind == BootCognitionEvidenceKind.CONTEXT })
        assertTrue(report.evidence.any { it.kind == BootCognitionEvidenceKind.DELTA_PHOTON })
    }

    @Test
    fun photonIdentityDependsOnReportContentNotPublicationClock() {
        val report = minimalReport()
        val factory = BootCognitionReportPhotonFactory()

        val first = factory.create(report, t0)
        val second = factory.create(report, t1)
        val changed = factory.create(
            report.copy(
                evidence = listOf(
                    BootCognitionEvidence(BootCognitionEvidenceKind.SNAPSHOT, "counts", "photons=1")
                )
            ),
            t0,
        )

        assertEquals(first.id, second.id)
        assertEquals(first.content, second.content)
        assertNotEquals(first.provenance.createdAt, second.provenance.createdAt)
        assertNotEquals(first.id, changed.id)
        assertEquals(report.sourcePhotonIds.toSet(), first.provenance.parentIds)
    }

    @Test
    fun publisherIsIdempotentAndVerifiesDurableReadAfterWrite() = runTest {
        val repository = InMemoryPhotonRepository()
        val publisher = BootCognitionReportPublisher(repository)
        val report = minimalReport()

        val first = publisher.publish(report, t0)
        val second = publisher.publish(report, t1)

        assertEquals(first, second)
        assertEquals(1, repository.saveCount)
        assertEquals(first, repository.load(first.id))
        assertEquals(t0, second.provenance.createdAt)
    }

    @Test
    fun recorderSkipsTrulyEmptyBootButPersistsNontrivialTruth() = runTest {
        val emptyRepository = InMemoryPhotonRepository()
        val emptyRecorder = recorder(emptyRepository, PhotonLoadReport(emptyList(), emptyList()))

        val empty = emptyRecorder.record()

        assertFalse(empty.persisted)
        assertNull(empty.photon)
        assertEquals(0, emptyRepository.saveCount)

        val source = photon("chat-2", "continue")
        val repository = InMemoryPhotonRepository()
        val nontrivial = recorder(
            repository,
            PhotonLoadReport(listOf(source), emptyList()),
        ).record()

        assertTrue(nontrivial.persisted)
        assertNotNull(nontrivial.photon)
        assertEquals(1, repository.saveCount)
        assertEquals(
            nontrivial.report,
            BootCognitionReportCodec.decode(nontrivial.photon!!.content),
        )
    }

    private fun recorder(
        repository: PhotonRepository,
        photonReport: PhotonLoadReport,
    ): BootCognitionEvidenceRecorder {
        val loader = BootSnapshotLoader(
            photons = BootPhotonSource { photonReport },
            tasks = BootTaskSource { TaskLoadReport(emptyList(), emptyList()) },
            checkpoints = BootCheckpointSource { CheckpointLoadReport(emptyList(), emptyList()) },
            capabilities = BootCapabilityStateSource { emptyList() },
            tools = BootToolStateSource { emptyList() },
            fieldSnapshots = BootFieldSnapshotSource { FieldSnapshotLoadReport(emptyList(), emptyList()) },
            now = { t0 },
        )
        return BootCognitionEvidenceRecorder(
            loader = loader,
            scanner = BootIntegrityScanner(now = { t0 }),
            contextRehydrator = BootContextRehydrator(),
            deltaAnalyzer = BootDeltaAnalyzer(),
            publisher = BootCognitionReportPublisher(repository),
        )
    }

    private fun minimalReport() = BootCognitionReport(
        currentGenerationId = generation,
        previousGenerationId = null,
        disposition = BootCognitionDisposition.CLEAN,
        snapshotPartial = false,
        sourcePhotonIds = listOf(PhotonId("source-1")),
        evidence = listOf(
            BootCognitionEvidence(BootCognitionEvidenceKind.SNAPSHOT, "counts", "photons=0")
        ),
    )

    private fun snapshot(
        photons: List<Photon> = emptyList(),
        contexts: List<BootContextProjection> = emptyList(),
        failures: List<BootSnapshotReadFailure> = emptyList(),
    ) = DurableBootSnapshot(
        generationId = generation,
        capturedAt = t0,
        photons = photons.sortedWith(compareBy<Photon>({ it.id.value }, { it.revision })),
        tasks = emptyList(),
        checkpoints = emptyList(),
        contexts = contexts.sortedWith(
            compareBy<BootContextProjection>(
                { it.kind.name }, { it.contextId }, { it.sourceCreatedAt },
                { it.sourcePhotonId.value }, { it.sourcePhotonRevision },
            )
        ),
        capabilities = emptyList(),
        workerLeases = emptyList(),
        tools = emptyList(),
        fieldSnapshots = emptyList(),
        readFailures = failures.sortedWith(compareBy({ it.source.name }, { it.entry ?: "" }, { it.reason })),
    )

    private fun photon(
        id: String,
        content: String,
        tags: Set<String> = emptySet(),
    ) = Photon(
        id = PhotonId(id),
        content = content,
        provenance = Provenance("test", "tester", t0),
        tags = tags,
    )

    private class InMemoryPhotonRepository : PhotonRepository {
        private val photons = linkedMapOf<PhotonId, Photon>()
        var saveCount: Int = 0
            private set

        override suspend fun save(photon: Photon) {
            saveCount += 1
            photons[photon.id] = photon
        }

        override suspend fun load(id: PhotonId): Photon? = photons[id]

        override suspend fun loadReport(): PhotonLoadReport = PhotonLoadReport(
            photons = photons.values.sortedBy { it.provenance.createdAt },
            unreadableFiles = emptyList(),
        )

        override suspend fun loadAll(): List<Photon> = photons.values.toList()

        override suspend fun delete(id: PhotonId) {
            photons.remove(id)
        }
    }
}
