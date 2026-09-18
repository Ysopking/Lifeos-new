package app.lifeos.core.runtime.escalation

import app.lifeos.core.runtime.health.HealthFailureCategory
import app.lifeos.core.runtime.health.HealthNodeId
import app.lifeos.core.runtime.health.HealthScope
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class EscalationLedgerTest {
    private val at = Instant.parse("2026-09-19T00:20:00Z")

    @Test
    fun decisionAndActionLifecycleReplaysDeterministically() = runBlocking {
        val repository = MemoryRepository()
        val ledger = EscalationLedger(repository) { at.plusSeconds(10) }
        val trigger = trigger()
        val opened = ledger.open(trigger)
        val decision = EscalationPolicy().decide(trigger, at.plusSeconds(1))
        val decided = ledger.decide(opened, decision)
        val started = ledger.markActionStarted(decided, "recovery-started", setOf("action-evidence"))
        val succeeded = ledger.markActionSucceeded(started, "recovery-verified")

        assertEquals(EscalationState.ACTION_SUCCEEDED, succeeded.state)
        assertEquals(EscalationLevel.L2_RECOVER_COMPONENT, succeeded.level)
        assertTrue("action-evidence" in succeeded.evidenceRefs)
        assertEquals(succeeded, ledger.snapshot(trigger.id))
        assertTrue(ledger.active().isEmpty())
    }

    @Test
    fun unreadableRepositoryFailsClosed() = runBlocking {
        val repository = MemoryRepository(unreadable = listOf("records/bad"))
        val ledger = EscalationLedger(repository)
        assertFailsWith<IllegalStateException> {
            ledger.open(trigger())
        }
    }

    @Test
    fun staleCasRetriesWithoutDuplicatingGlobalRevision() = runBlocking {
        val repository = MemoryRepository(failFirstAppend = true)
        val ledger = EscalationLedger(repository)
        val opened = ledger.open(trigger())
        assertEquals(1L, opened.ledgerRevision)
        assertEquals(listOf(1L), repository.records.map { it.revision })
    }

    private fun trigger() = EscalationTrigger(
        nodeId = HealthNodeId("runtime"),
        scope = HealthScope.RUNTIME,
        category = HealthFailureCategory.RECOVERY,
        recoverable = true,
        consecutiveFailures = 1,
        retryBudgetRemaining = false,
        observedAt = at,
        evidenceRefs = setOf("health-observation-1"),
    )

    private class MemoryRepository(
        private val unreadable: List<String> = emptyList(),
        private var failFirstAppend: Boolean = false,
    ) : EscalationRepository {
        val records = mutableListOf<EscalationRecord>()

        override suspend fun loadReport(): EscalationRepositoryLoadReport =
            EscalationRepositoryLoadReport(records.toList(), unreadable)

        override suspend fun append(expectedRevision: Long, record: EscalationRecord): Boolean {
            if (failFirstAppend) {
                failFirstAppend = false
                return false
            }
            val current = records.lastOrNull()?.revision ?: 0L
            if (current != expectedRevision) return false
            records += record
            return true
        }
    }
}
