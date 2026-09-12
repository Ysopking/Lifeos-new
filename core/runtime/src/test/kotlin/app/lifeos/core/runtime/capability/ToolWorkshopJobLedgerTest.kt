package app.lifeos.core.runtime.capability

import app.lifeos.core.model.PhotonId
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolWorkshopJobLedgerTest {
    @Test
    fun `equivalent repeated requests deduplicate to one durable job and tool identity`() = runTest {
        val first = definition(requestedAt = NOW, requestedBy = "router-a")
        val second = definition(requestedAt = NOW.plusSeconds(30), requestedBy = "router-b")

        assertEquals(first.id, second.id)
        assertEquals(first.deterministicToolId, second.deterministicToolId)
        assertTrue(first.sameIdentityAs(second))
        assertFalse(first.sourceRequestId == second.sourceRequestId)

        val ledger = ToolWorkshopJobLedger(MemoryRepository()) { NOW }
        val created = ledger.create(first)
        val deduplicated = ledger.create(second)

        assertEquals(created, deduplicated)
        assertEquals(ToolWorkshopJobState.REQUESTED, deduplicated.state)
        assertEquals(first.sourceRequestId, deduplicated.definition.sourceRequestId)
    }

    @Test
    fun `restart reconstructs exact stage and deterministic tool identity`() = runTest {
        val repository = MemoryRepository()
        val firstLedger = ToolWorkshopJobLedger(repository) { NOW }
        var snapshot = firstLedger.create(definition())
        snapshot = firstLedger.advance(snapshot, ToolWorkshopJobState.SPECIFIED, "spec:sha256")
        snapshot = firstLedger.advance(snapshot, ToolWorkshopJobState.DESIGNED, "design:sha256")
        snapshot = firstLedger.advance(snapshot, ToolWorkshopJobState.IMPLEMENTED, "source:sha256")

        val restored = requireNotNull(
            ToolWorkshopJobLedger(repository) { NOW.plusSeconds(10) }.snapshot(snapshot.definition.id)
        )

        assertEquals(ToolWorkshopJobState.IMPLEMENTED, restored.state)
        assertEquals("source:sha256", restored.stageFingerprint)
        assertEquals(snapshot.toolId, restored.toolId)
        assertEquals(snapshot.definition, restored.definition)
    }

    @Test
    fun `stage skipping and changed replay fingerprint are rejected`() = runTest {
        val ledger = ToolWorkshopJobLedger(MemoryRepository()) { NOW }
        val requested = ledger.create(definition())

        assertFailsWith<IllegalArgumentException> {
            ledger.advance(requested, ToolWorkshopJobState.BUILT, "build:sha256")
        }

        val specified = ledger.advance(requested, ToolWorkshopJobState.SPECIFIED, "spec:one")
        assertFailsWith<IllegalArgumentException> {
            ledger.advance(specified, ToolWorkshopJobState.SPECIFIED, "spec:two")
        }
    }

    @Test
    fun `terminal rejection survives reconstruction and cannot resume`() = runTest {
        val repository = MemoryRepository()
        val ledger = ToolWorkshopJobLedger(repository) { NOW }
        val requested = ledger.create(definition())
        val rejected = ledger.reject(requested, "security-policy-denied")

        val restored = requireNotNull(ToolWorkshopJobLedger(repository).snapshot(rejected.definition.id))
        assertEquals(ToolWorkshopJobState.REJECTED, restored.state)
        assertTrue(restored.terminal)
        assertEquals("security-policy-denied", restored.lastDetail)
        assertFailsWith<IllegalArgumentException> {
            ledger.advance(restored, ToolWorkshopJobState.SPECIFIED, "spec:sha256")
        }
    }

    @Test
    fun `codec roundtrip preserves events and rejects trailing corruption`() = runTest {
        val repository = MemoryRepository()
        val ledger = ToolWorkshopJobLedger(repository) { NOW }
        var snapshot = ledger.create(definition())
        snapshot = ledger.advance(snapshot, ToolWorkshopJobState.SPECIFIED, "spec:sha256")
        ledger.advance(snapshot, ToolWorkshopJobState.DESIGNED, "design:sha256")
        val events = repository.loadReport().events
        val encoded = ToolWorkshopJobEventLogCodec.encode(events)

        assertEquals(events, ToolWorkshopJobEventLogCodec.decode(encoded))
        assertFailsWith<IllegalArgumentException> {
            ToolWorkshopJobEventLogCodec.decode(encoded + byteArrayOf(1))
        }
    }

    @Test
    fun `unreadable workshop ledger fails closed`() = runTest {
        val repository = object : ToolWorkshopJobRepository {
            override suspend fun loadReport() = ToolWorkshopJobRepositoryLoadReport(
                events = emptyList(),
                unreadableEntries = listOf("corrupt-workshop-ledger"),
            )

            override suspend fun append(expectedRevision: Long, event: ToolWorkshopJobEvent): Boolean = false
        }

        assertFailsWith<IllegalStateException> {
            ToolWorkshopJobLedger(repository).all()
        }
    }

    private fun definition(
        requestedAt: Instant = NOW,
        requestedBy: String = "router",
    ): ToolWorkshopJobDefinition {
        val request = GeneratedToolRequest.fromGap(
            gap = CapabilityGap(
                requirement = CapabilityRequirement(
                    capabilityId = CAPABILITY,
                    severity = GapSeverity.BLOCKING,
                    requiredInputs = setOf("text"),
                    requiredOutputs = setOf("normalized-text"),
                ),
                type = CapabilityGapType.CAPABILITY_MISSING,
                candidateProviderIds = emptyList(),
            ),
            requestPhotonId = PhotonId("photon-workshop-source"),
            requestedBy = requestedBy,
            requestedAt = requestedAt,
        )
        return ToolWorkshopJobDefinition.fromRequest(
            request = request,
            sourceRevision = 7L,
            policyVersion = "private-tool-policy-v1",
            workshopVersion = "tool-workshop-v11",
        )
    }

    private class MemoryRepository : ToolWorkshopJobRepository {
        private val events = mutableListOf<ToolWorkshopJobEvent>()

        override suspend fun loadReport() = ToolWorkshopJobRepositoryLoadReport(events.toList())

        override suspend fun append(expectedRevision: Long, event: ToolWorkshopJobEvent): Boolean {
            val current = events.lastOrNull()?.revision ?: 0L
            if (current != expectedRevision) return false
            require(event.revision == current + 1L)
            events += event
            return true
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-11T14:20:00Z")
        val CAPABILITY = CapabilityId("text.normalize.v11")
    }
}
