package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.PhotonIndexReport
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.PhotonRevisionWriteResult
import app.lifeos.core.model.RevisionedPhotonRepository
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskState
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CognitionJournalIndexRecoveryTest {
    private val t0 = Instant.parse("2026-09-18T12:00:00Z")

    @Test
    fun indexedJournalHotPathsNeverUseFullPhotonLoadReport() = runTest {
        val photons = CountingRevisionedPhotonRepository()
        val index = CognitionJournalIndex(InMemoryIndexRepository(), photons)

        val event = CognitiveEvent(
            eventId = "event-1",
            delta = PhotonDelta(
                deltaId = "delta-1",
                source = "test",
                photonId = PhotonId("source-1"),
                revisionAfter = 1,
                type = PhotonDeltaType.CREATED,
                timestamp = t0,
            ),
            recordedAt = t0,
        )
        val events = PhotonBackedRuntimeEventJournal(photons, index)
        assertEquals(1L, events.append(event))
        assertEquals(1L, events.size())
        assertEquals(listOf(event), events.readFrom(0).map { it.event })

        val transaction = PhotonTransactionRecord(
            transactionId = "tx-1",
            taskId = TaskId("task-1"),
            photonId = PhotonId("source-1"),
            state = PhotonTransactionState.COMMITTED,
            influences = emptyList(),
            failures = emptyList(),
            recordedAt = t0.plusSeconds(1),
        )
        val transactions = PhotonBackedPhotonTransactionJournal(photons, index)
        assertTrue(transactions.record(transaction))
        assertFalse(transactions.record(transaction))
        assertEquals(listOf(transaction), transactions.latest())

        val outcome = CognitiveOutcome(
            taskId = TaskId("task-1"),
            photonId = PhotonId("source-1"),
            finalState = TaskState.COMPLETED,
            influences = emptyList(),
            failures = emptyList(),
            recordedAt = t0.plusSeconds(2),
        )
        val outcomes = PhotonBackedCognitiveOutcomeJournal(photons, index)
        assertEquals(1L, outcomes.record(outcome))
        assertEquals(1L, outcomes.record(outcome.copy(recordedAt = t0.plusSeconds(30))))
        assertEquals(listOf(outcome), outcomes.latest())

        val trigger = CognitiveTrigger(
            id = "trigger-1",
            type = CognitiveTriggerType.RECOVERY,
            sourceTaskId = TaskId("task-1"),
            photonId = PhotonId("source-1"),
            reason = "recoverable",
            createdAt = t0.plusSeconds(3),
        )
        val triggers = PhotonBackedCognitiveTriggerSink(photons, index)
        assertTrue(triggers.emit(trigger))
        assertFalse(triggers.emit(trigger))
        assertEquals(listOf(trigger), triggers.snapshot())

        val integrity = CognitionJournalIntegrityVerifier(photons, index).verify()
        assertEquals(4, integrity.total)
        assertEquals(0, photons.loadReportCalls)
    }

    @Test
    fun pendingReservationRecoversPhotonWrittenBeforeIndexCommit() = runTest {
        val photons = CountingRevisionedPhotonRepository()
        val durableIndex = InMemoryIndexRepository()
        val firstProcess = CognitionJournalIndex(durableIndex, photons)
        val event = CognitiveEvent(
            eventId = "event-crash-window",
            delta = PhotonDelta(
                deltaId = "delta-crash-window",
                source = "test",
                photonId = PhotonId("source-crash"),
                revisionAfter = 1,
                type = PhotonDeltaType.CREATED,
                timestamp = t0,
            ),
            recordedAt = t0,
        )

        val reservation = firstProcess.reserveNext(CognitionJournalKind.EVENT, event.eventId)
        photons.save(
            cognitionJournalPhoton(
                kind = CognitionJournalKind.EVENT,
                stableId = event.eventId,
                at = event.recordedAt,
                content = RuntimeEventJournalCodec.encode(
                    RuntimeEventEnvelope(reservation.sequence, event)
                ),
            )
        )

        val afterRestart = CognitionJournalIndex(durableIndex, photons)
        val report = afterRestart.reconcile()
        val snapshot = afterRestart.snapshot()

        assertTrue(report.rebuilt)
        assertEquals(1, report.recoveredPendingReservations)
        assertEquals(1L, snapshot.head(CognitionJournalKind.EVENT))
        assertEquals(1L, snapshot.size(CognitionJournalKind.EVENT))
        assertTrue(snapshot.pendingReservations.isEmpty())
        assertEquals(0, photons.loadReportCalls)
    }

    private class InMemoryIndexRepository : CognitionJournalIndexRepository {
        var value: CognitionJournalIndexSnapshot? = null

        override suspend fun load(): CognitionJournalIndexSnapshot? = value

        override suspend fun save(snapshot: CognitionJournalIndexSnapshot) {
            value = snapshot
        }
    }

    private class CountingRevisionedPhotonRepository : RevisionedPhotonRepository {
        private val values = linkedMapOf<PhotonId, Photon>()
        var loadReportCalls: Int = 0
            private set

        override suspend fun save(photon: Photon) {
            val current = values[photon.id]
            require(current == null || current == photon) { "Photon identity collision" }
            values[photon.id] = photon
        }

        override suspend fun saveRevision(
            photon: Photon,
            expectedPreviousRevision: Long?,
        ): PhotonRevisionWriteResult {
            val current = values[photon.id]
            if (current == null) {
                values[photon.id] = photon
                return PhotonRevisionWriteResult.Created(photon)
            }
            if (current == photon) {
                return PhotonRevisionWriteResult.Idempotent(photon, current)
            }
            return PhotonRevisionWriteResult.Conflict(
                photon = photon,
                previous = current,
                reason = "test-conflict",
            )
        }

        override suspend fun load(id: PhotonId): Photon? = values[id]

        override suspend fun load(ref: PhotonRevisionRef): Photon? =
            values[ref.photonId]?.takeIf { it.revision == ref.revision }

        override suspend fun latestRef(id: PhotonId): PhotonRevisionRef? =
            values[id]?.let { PhotonRevisionRef(it.id, it.revision) }

        override suspend fun query(query: PhotonIndexQuery): List<PhotonRevisionRef> =
            values.values.asSequence()
                .filter { query.ids.isEmpty() || it.id in query.ids }
                .filter { query.phases.isEmpty() || it.phase in query.phases }
                .filter { query.mimeTypes.isEmpty() || it.mimeType in query.mimeTypes }
                .filter { it.tags.containsAll(query.allTags) }
                .sortedBy { it.id.value }
                .take(query.limit)
                .map { PhotonRevisionRef(it.id, it.revision) }
                .toList()

        override suspend fun indexReport(): PhotonIndexReport = PhotonIndexReport(
            formatVersion = 1,
            entryCount = values.size,
            livePhotonCount = values.size,
            tombstonedPhotonCount = 0,
            latestRefs = values.values.associate { it.id to PhotonRevisionRef(it.id, it.revision) },
        )

        override suspend fun loadAll(): List<Photon> = values.values.toList()

        override suspend fun loadReport(): PhotonLoadReport {
            loadReportCalls += 1
            return PhotonLoadReport(loadAll(), emptyList())
        }

        override suspend fun delete(id: PhotonId) {
            values.remove(id)
        }
    }
}
