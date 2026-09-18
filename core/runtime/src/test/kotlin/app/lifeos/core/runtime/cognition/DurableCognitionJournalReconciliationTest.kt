package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.tasks.DurableTaskEngine
import app.lifeos.core.runtime.tasks.InMemoryTaskRepository
import app.lifeos.core.runtime.tasks.TaskSchedulerSignal
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DurableCognitionJournalReconciliationTest {
    @Test
    fun internalJournalPhotonsAreNeverRescheduledAsCognitiveEvidence() = runTest {
        val now = Instant.parse("2026-09-12T10:00:00Z")
        val normal = Photon(
            id = PhotonId("normal-evidence"),
            content = "normal evidence",
            provenance = Provenance("test", "test", now),
        )
        val journal = cognitionJournalPhoton(
            kind = CognitionJournalKind.EVENT,
            stableId = "internal-event",
            at = now,
            content = RuntimeEventJournalCodec.encode(
                RuntimeEventEnvelope(
                    offset = 1,
                    event = CognitiveEvent(
                        eventId = "internal-event",
                        delta = PhotonDelta(
                            deltaId = "internal-delta",
                            source = "test",
                            photonId = normal.id,
                            revisionAfter = 1,
                            type = PhotonDeltaType.CREATED,
                            timestamp = now,
                        ),
                        recordedAt = now,
                    ),
                )
            ),
        )
        val photons = TestPhotonRepository(listOf(normal, journal))
        val tasks = InMemoryTaskRepository()
        val taskEngine = DurableTaskEngine(
            tasks = tasks,
            schedulerSignal = TaskSchedulerSignal { },
            now = { now },
        )
        val cognition = ContinuousCognitionEngine(
            journal = InMemoryCognitiveEventJournal(),
            scheduler = CognitiveScheduler(),
            durableDispatcher = DurableCognitionDispatcher(taskEngine),
        )
        val reconciler = DurableCognitionReconciler(
            photons = photons,
            tasks = tasks,
            cognition = cognition,
            taskEngine = taskEngine,
            coverage = CognitionCoverageIndex(MemoryCoverageRepository()),
        )

        val result = reconciler.reconcile()
        val durableTasks = tasks.loadReport().tasks

        assertEquals(2, result.scannedPhotons)
        assertEquals(1, result.submitted)
        assertEquals(1, durableTasks.size)
        assertEquals(setOf(normal.id), durableTasks.single().inputPhotonIds)
    }

    private class MemoryCoverageRepository : CognitionCoverageRepository {
        private var snapshot: CognitionCoverageSnapshot? = null

        override suspend fun load(): CognitionCoverageSnapshot? = snapshot

        override suspend fun save(snapshot: CognitionCoverageSnapshot) {
            this.snapshot = snapshot
        }
    }

    private class TestPhotonRepository(initial: List<Photon>) : PhotonRepository {
        private val values = initial.associateByTo(linkedMapOf()) { it.id }

        override suspend fun save(photon: Photon) {
            values[photon.id] = photon
        }

        override suspend fun load(id: PhotonId): Photon? = values[id]
        override suspend fun loadAll(): List<Photon> = values.values.toList()
        override suspend fun loadReport(): PhotonLoadReport = PhotonLoadReport(loadAll(), emptyList())
        override suspend fun delete(id: PhotonId) {
            values.remove(id)
        }
    }
}
