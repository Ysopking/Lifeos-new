package app.lifeos.core.runtime.cognition

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskState
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DurableCognitionJournalTest {
    private val t0 = Instant.parse("2026-09-12T10:00:00Z")

    @Test
    fun eventJournalSurvivesRecreationAndRejectsConflictingIdentity() = runTest {
        val store = TestPhotonRepository()
        val event = event("event-1", "delta-1")
        val first = PhotonBackedRuntimeEventJournal(store)

        assertEquals(1L, first.append(event))
        assertEquals(1L, first.append(event))

        val recreated = PhotonBackedRuntimeEventJournal(store)
        assertEquals(listOf(event), recreated.readFrom(0).map { it.event })
        assertEquals(1L, recreated.size())

        val conflict = event.copy(delta = event.delta.copy(deltaId = "different"))
        val error = try {
            recreated.append(conflict)
            null
        } catch (failure: Throwable) {
            failure
        }
        assertIs<IllegalStateException>(error)
    }

    @Test
    fun transactionOutcomeAndTriggerSurviveRecreationAndStayIdempotent() = runTest {
        val store = TestPhotonRepository()
        val transaction = PhotonTransactionRecord(
            transactionId = "tx-1",
            taskId = TaskId("task-1"),
            photonId = PhotonId("photon-1"),
            state = PhotonTransactionState.COMMITTED,
            influences = emptyList(),
            failures = emptyList(),
            recordedAt = t0,
        )
        val transactions = PhotonBackedPhotonTransactionJournal(store)
        assertTrue(transactions.record(transaction))
        assertFalse(transactions.record(transaction))
        assertEquals(transaction, PhotonBackedPhotonTransactionJournal(store).get("tx-1"))

        val outcome = CognitiveOutcome(
            taskId = TaskId("task-1"),
            photonId = PhotonId("photon-1"),
            finalState = TaskState.COMPLETED,
            influences = emptyList(),
            failures = emptyList(),
            recordedAt = t0,
        )
        val outcomes = PhotonBackedCognitiveOutcomeJournal(store)
        assertEquals(1L, outcomes.record(outcome))
        assertEquals(1L, outcomes.record(outcome.copy(recordedAt = t0.plusSeconds(30))))
        assertEquals(1, PhotonBackedCognitiveOutcomeJournal(store).latest().size)

        val trigger = CognitiveTrigger(
            id = "trigger-1",
            type = CognitiveTriggerType.RECOVERY,
            sourceTaskId = TaskId("task-1"),
            photonId = PhotonId("photon-1"),
            reason = "recoverable-task-failure",
            createdAt = t0,
        )
        val triggers = PhotonBackedCognitiveTriggerSink(store)
        assertTrue(triggers.emit(trigger))
        assertFalse(triggers.emit(trigger))
        assertEquals(listOf(trigger), PhotonBackedCognitiveTriggerSink(store).snapshot())

        val conflict = trigger.copy(reason = "different-reason")
        val error = try {
            PhotonBackedCognitiveTriggerSink(store).emit(conflict)
            null
        } catch (failure: Throwable) {
            failure
        }
        assertIs<IllegalStateException>(error)
    }

    @Test
    fun journalPhotonsRemainArchivedInternalAndIntegrityVerified() = runTest {
        val store = TestPhotonRepository()
        PhotonBackedRuntimeEventJournal(store).append(event("event-1", "delta-1"))
        PhotonBackedPhotonTransactionJournal(store).record(
            PhotonTransactionRecord(
                transactionId = "tx-1",
                taskId = TaskId("task-1"),
                photonId = null,
                state = PhotonTransactionState.COMMITTED,
                influences = emptyList(),
                failures = emptyList(),
                recordedAt = t0,
            )
        )
        PhotonBackedCognitiveOutcomeJournal(store).record(
            CognitiveOutcome(
                taskId = TaskId("task-1"),
                photonId = null,
                finalState = TaskState.COMPLETED,
                influences = emptyList(),
                failures = emptyList(),
                recordedAt = t0,
            )
        )
        PhotonBackedCognitiveTriggerSink(store).emit(
            CognitiveTrigger(
                id = "trigger-1",
                type = CognitiveTriggerType.QUARANTINE_REVIEW,
                sourceTaskId = TaskId("task-1"),
                photonId = null,
                reason = "review",
                createdAt = t0,
            )
        )

        val journalPhotons = store.loadAll().filter { COGNITION_JOURNAL_ROOT_TAG in it.tags }
        assertEquals(4, journalPhotons.size)
        assertTrue(journalPhotons.all { it.phase == PhotonPhase.ARCHIVED })
        assertTrue(journalPhotons.all { it.semanticMass == 0.0 && it.energy == 0.0 })
        assertTrue(journalPhotons.all { it.mimeType == COGNITION_JOURNAL_MIME })

        val report = CognitionJournalIntegrityVerifier(store).verify()
        assertEquals(4, report.total)
        assertEquals(1, report.events)
        assertEquals(1, report.transactions)
        assertEquals(1, report.outcomes)
        assertEquals(1, report.triggers)
    }

    @Test
    fun malformedJournalPayloadIsContainedAsCorruption() = runTest {
        val store = TestPhotonRepository()
        store.save(
            cognitionJournalPhoton(
                kind = CognitionJournalKind.EVENT,
                stableId = "bad-event",
                at = t0,
                content = "not-a-valid-runtime-event",
            )
        )

        val error = try {
            CognitionJournalIntegrityVerifier(store).verify()
            null
        } catch (failure: Throwable) {
            failure
        }
        assertIs<CognitionJournalCorruptionException>(error)
    }

    private fun event(eventId: String, deltaId: String): CognitiveEvent = CognitiveEvent(
        eventId = eventId,
        delta = PhotonDelta(
            deltaId = deltaId,
            source = "test",
            photonId = PhotonId("photon-1"),
            revisionAfter = 1,
            type = PhotonDeltaType.CREATED,
            timestamp = t0,
        ),
        recordedAt = t0,
    )

    private class TestPhotonRepository : PhotonRepository {
        private val values = linkedMapOf<PhotonId, Photon>()

        override suspend fun save(photon: Photon) {
            values[photon.id] = photon
        }

        override suspend fun load(id: PhotonId): Photon? = values[id]

        override suspend fun loadAll(): List<Photon> = values.values.toList()

        override suspend fun loadReport(): PhotonLoadReport = PhotonLoadReport(
            photons = loadAll(),
            unreadableFiles = emptyList(),
        )

        override suspend fun delete(id: PhotonId) {
            values.remove(id)
        }
    }
}
