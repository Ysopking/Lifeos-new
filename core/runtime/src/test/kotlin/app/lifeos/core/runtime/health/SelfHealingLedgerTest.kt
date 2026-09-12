package app.lifeos.core.runtime.health

import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class SelfHealingLedgerTest {
    @Test
    fun `prepared action remains in flight after ledger reconstruction`() = runTest {
        val repository = MemorySelfHealingRepository()
        val ledger = SelfHealingLedger(repository) { NOW }
        val plan = plan()
        val opened = ledger.open(plan, "incident-a")
        val prepared = ledger.markPrepared(opened, 0, "repair-a")
        assertEquals(SelfHealingIncidentState.ACTION_IN_FLIGHT, prepared.state)

        val reconstructed = SelfHealingLedger(repository) { NOW.plusSeconds(1) }
        val restored = requireNotNull(reconstructed.snapshot(opened.incidentId))
        assertEquals(SelfHealingIncidentState.ACTION_IN_FLIGHT, restored.state)
        assertEquals(0, restored.inFlightActionIndex)
        assertEquals("repair-a", restored.inFlightActionId)
        assertEquals(listOf("repair-a"), restored.attemptedActionIds)
    }

    @Test
    fun `terminal recovered replay stays terminal`() = runTest {
        val repository = MemorySelfHealingRepository()
        val ledger = SelfHealingLedger(repository) { NOW }
        val opened = ledger.open(plan(), "incident-b")
        val prepared = ledger.markPrepared(opened, 0, "repair-a")
        val recovered = ledger.markRecovered(prepared, "ok", "probe:healthy")

        assertEquals(SelfHealingIncidentState.RECOVERED, recovered.state)
        assertEquals(true, recovered.terminal)
        assertEquals(
            SelfHealingIncidentState.RECOVERED,
            requireNotNull(SelfHealingLedger(repository).snapshot(opened.incidentId)).state,
        )
    }

    @Test
    fun `codec roundtrip preserves events and rejects trailing corruption`() = runTest {
        val repository = MemorySelfHealingRepository()
        val ledger = SelfHealingLedger(repository) { NOW }
        val opened = ledger.open(plan(), "incident-c")
        ledger.markPrepared(opened, 0, "repair-a")
        val events = repository.loadReport().events

        val encoded = SelfHealingEventLogCodec.encode(events)
        assertEquals(events, SelfHealingEventLogCodec.decode(encoded))
        assertFailsWith<IllegalArgumentException> {
            SelfHealingEventLogCodec.decode(encoded + byteArrayOf(1))
        }
    }

    @Test
    fun `unreadable ledger fails closed`() = runTest {
        val repository = object : SelfHealingRepository {
            override suspend fun loadReport() = SelfHealingRepositoryLoadReport(
                events = emptyList(),
                unreadableEntries = listOf("corrupt"),
            )
            override suspend fun append(expectedRevision: Long, event: SelfHealingEvent): Boolean = false
        }
        assertFailsWith<IllegalStateException> {
            SelfHealingLedger(repository).active()
        }
    }

    private fun plan() = RecoveryPlan(
        nodeId = HealthNodeId("runtime"),
        source = "test",
        actions = listOf(object : RecoveryAction {
            override val id = "repair-a"
            override suspend fun execute(): RecoveryActionResult = RecoveryActionResult.Success()
        }),
        verificationProbes = listOf(
            WorkerRepairProbe("probe", HealthNodeId("runtime")) {
                RepairProbeObservation(RepairProbeStatus.HEALTHY)
            }
        ),
    )

    private class MemorySelfHealingRepository : SelfHealingRepository {
        private val events = mutableListOf<SelfHealingEvent>()
        override suspend fun loadReport() = SelfHealingRepositoryLoadReport(events.toList())
        override suspend fun append(expectedRevision: Long, event: SelfHealingEvent): Boolean {
            val current = events.lastOrNull()?.revision ?: 0L
            if (current != expectedRevision) return false
            require(event.revision == expectedRevision + 1L)
            events += event
            return true
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-11T13:00:00Z")
    }
}
