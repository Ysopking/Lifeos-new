package app.lifeos.core.runtime.cognition

import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContinuousCognitionEngineTest {
    private val budget = CognitiveWorkBudget(
        maxDurationMs = 1_000,
        maxModuleInvocations = 2,
        maxNewPhotons = 4,
        maxNetworkCalls = 0,
    )

    @Test
    fun repeatedDeltaUsesSameJournalOffsetAndWorkIdentity() = runTest {
        val journal = InMemoryCognitiveEventJournal()
        val scheduler = CognitiveScheduler()
        val engine = ContinuousCognitionEngine(journal, scheduler)
        val delta = PhotonDelta(
            deltaId = "delta-1",
            source = "test",
            type = PhotonDeltaType.CREATED,
            timestamp = Instant.parse("2026-09-07T18:00:00Z"),
        )
        val salience = SalienceVector(relevance = 1.0, urgency = 0.5)

        val first = engine.submit(delta, CognitivePriority.HIGH, salience, setOf("memory"), budget)
        val second = engine.submit(delta, CognitivePriority.HIGH, salience, setOf("memory"), budget)

        assertEquals(first.journalOffset, second.journalOffset)
        assertEquals(first.workId, second.workId)
        assertEquals(1, journal.size())
        assertEquals(1, scheduler.size())
    }

    @Test
    fun processingLedgerPreventsConcurrentDuplicateWorkUntilAbort() = runTest {
        val ledger = CognitiveProcessingLedger()
        val key = CognitiveProcessingKey(
            deltaId = "delta-1",
            moduleId = "memory",
            operation = "evaluate",
            inputRevision = 1,
        )

        assertTrue(ledger.tryStart(key))
        assertFalse(ledger.tryStart(key))
        assertTrue(ledger.abort(key))
        assertTrue(ledger.tryStart(key))
        assertTrue(ledger.commit(key))
        assertFalse(ledger.tryStart(key))
        assertEquals(ProcessingState.COMMITTED, ledger.state(key))
    }
}
