package app.lifeos.core.runtime.capability

import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HotSwapLedgerTest {
    @Test
    fun `prepared promoted committed transaction replays exactly after reconstruction`() = runTest {
        val repository = MemoryHotSwapRepository()
        val ledger = HotSwapLedger(repository) { NOW }
        val prepared = ledger.prepare(
            capabilityId = CAPABILITY,
            previousToolId = "tool-old",
            candidateToolId = "tool-new",
            previousPromotionEvidenceId = "promotion-old",
            candidatePromotionEvidenceId = "promotion-new",
        )
        val promoted = ledger.markCandidatePromoted(
            prepared,
            ownerPolicyRevision = 7L,
            worldSnapshotId = "world:hot-swap:1",
        )
        val committed = ledger.markCommitted(promoted)

        val restored = requireNotNull(
            HotSwapLedger(repository) { NOW.plusSeconds(1) }.snapshot(prepared.transactionId)
        )
        assertEquals(HotSwapState.COMMITTED, restored.state)
        assertEquals(committed, restored)
        assertEquals(7L, restored.ownerPolicyRevision)
        assertEquals("world:hot-swap:1", restored.worldSnapshotId)
        assertTrue(restored.terminal)
    }

    @Test
    fun `terminal transaction cannot transition again`() = runTest {
        val ledger = HotSwapLedger(MemoryHotSwapRepository()) { NOW }
        val prepared = ledger.prepare(
            CAPABILITY,
            "tool-old",
            "tool-new",
            "promotion-old",
            "promotion-new",
        )
        val blocked = ledger.markBlocked(prepared, "owner-policy-blocked")

        assertFailsWith<IllegalArgumentException> {
            ledger.markRolledBack(blocked, "late-rollback")
        }
    }

    @Test
    fun `codec roundtrip preserves ledger and rejects trailing corruption`() = runTest {
        val repository = MemoryHotSwapRepository()
        val ledger = HotSwapLedger(repository) { NOW }
        val prepared = ledger.prepare(
            CAPABILITY,
            "tool-old",
            "tool-new",
            "promotion-old",
            "promotion-new",
        )
        ledger.markCandidatePromoted(prepared, 4L, "world:snapshot")
        val events = repository.loadReport().events
        val encoded = HotSwapEventLogCodec.encode(events)

        assertEquals(events, HotSwapEventLogCodec.decode(encoded))
        assertFailsWith<IllegalArgumentException> {
            HotSwapEventLogCodec.decode(encoded + byteArrayOf(1))
        }
    }

    @Test
    fun `unreadable ledger fails closed`() = runTest {
        val repository = object : HotSwapRepository {
            override suspend fun loadReport() = HotSwapRepositoryLoadReport(
                events = emptyList(),
                unreadableEntries = listOf("corrupt"),
            )

            override suspend fun append(expectedRevision: Long, event: HotSwapEvent): Boolean = false
        }

        assertFailsWith<IllegalStateException> {
            HotSwapLedger(repository).all()
        }
    }

    private class MemoryHotSwapRepository : HotSwapRepository {
        private val events = mutableListOf<HotSwapEvent>()

        override suspend fun loadReport() = HotSwapRepositoryLoadReport(events.toList())

        override suspend fun append(expectedRevision: Long, event: HotSwapEvent): Boolean {
            val current = events.lastOrNull()?.revision ?: 0L
            if (current != expectedRevision) return false
            require(event.revision == expectedRevision + 1L)
            events += event
            return true
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-11T14:00:00Z")
        val CAPABILITY = CapabilityId("text.normalize")
    }
}
