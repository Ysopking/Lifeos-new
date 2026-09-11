package app.lifeos.core.runtime.deepsearch

import app.lifeos.core.model.PhotonId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DeepSearchMissionLedgerTest {
    @Test
    fun `mission identity is deterministic across equivalent source scope order`() {
        val first = definition(setOf("local-memory", "local-photons"))
        val second = definition(setOf("local-photons", "local-memory"))
        assertEquals(first.id, second.id)
        assertTrue(first.id.value.matches(Regex("deep-search-mission_[0-9a-f]{64}")))
    }

    @Test
    fun `restart reconstructs exact checkpoint and terminal result binding`() = runBlocking {
        val repo = MemoryRepository()
        val first = DeepSearchMissionLedger(repo, now = { NOW })
        var snapshot = first.create(definition())
        snapshot = first.startExploring(snapshot)
        snapshot = first.checkpoint(snapshot, "checkpoint-1")
        snapshot = first.startSynthesizing(snapshot)
        snapshot = first.startVerifying(snapshot)
        snapshot = first.unresolved(snapshot, PhotonId("deep_search_result_1"))

        val restored = DeepSearchMissionLedger(repo, now = { NOW }).snapshot(snapshot.definition.id)!!
        assertEquals(DeepSearchMissionState.UNRESOLVED, restored.state)
        assertEquals("checkpoint-1", restored.checkpointFingerprint)
        assertEquals(PhotonId("deep_search_result_1"), restored.resultPhotonId)
        assertTrue(restored.terminal)
    }

    @Test
    fun `same checkpoint fingerprint is idempotent`() = runBlocking {
        val repo = MemoryRepository()
        val ledger = DeepSearchMissionLedger(repo, now = { NOW })
        var snapshot = ledger.startExploring(ledger.create(definition()))
        snapshot = ledger.checkpoint(snapshot, "checkpoint-1")
        val revision = snapshot.ledgerRevision
        val replay = ledger.checkpoint(snapshot, "checkpoint-1")
        assertEquals(revision, replay.ledgerRevision)
    }

    @Test
    fun `terminal mission cannot resume exploration`() = runBlocking {
        val ledger = DeepSearchMissionLedger(MemoryRepository(), now = { NOW })
        var snapshot = ledger.startExploring(ledger.create(definition()))
        snapshot = ledger.block(snapshot, "source-policy-blocked")
        assertTrue(snapshot.terminal)
        assertFailsWith<IllegalArgumentException> { ledger.startExploring(snapshot) }
    }

    @Test
    fun `codec rejects trailing corruption`() {
        val definition = definition()
        val event = DeepSearchMissionEvent(
            revision = 1,
            missionId = definition.id,
            type = DeepSearchMissionEventType.PLANNED,
            recordedAt = NOW,
            definition = definition,
        )
        val encoded = DeepSearchMissionEventLogCodec.encode(listOf(event))
        assertEquals(listOf(event), DeepSearchMissionEventLogCodec.decode(encoded))
        assertFailsWith<Exception> {
            DeepSearchMissionEventLogCodec.decode(encoded + byteArrayOf(1))
        }
    }

    @Test
    fun `unreadable repository fails closed`() = runBlocking {
        val ledger = DeepSearchMissionLedger(object : DeepSearchMissionRepository {
            override suspend fun loadReport() = DeepSearchMissionRepositoryLoadReport(
                events = emptyList(),
                unreadableEntries = listOf("missions.dsmission"),
            )
            override suspend fun append(expectedRevision: Long, event: DeepSearchMissionEvent) = false
        })
        assertFailsWith<IllegalStateException> { ledger.all() }
    }

    private fun definition(scopes: Set<String> = setOf("local-photons")) = DeepSearchMissionDefinition.create(
        goalPhotonId = PhotonId("goal_1"),
        sourcePhotonId = PhotonId("source_1"),
        sourceRevision = 3,
        query = "  LIFEOS   search  ",
        searchPolicyVersion = "deepsearch-v2-policy-1",
        sourceScopeIds = scopes,
        createdAt = NOW,
    )

    private class MemoryRepository : DeepSearchMissionRepository {
        private val events = mutableListOf<DeepSearchMissionEvent>()
        override suspend fun loadReport() = DeepSearchMissionRepositoryLoadReport(events.toList())
        override suspend fun append(expectedRevision: Long, event: DeepSearchMissionEvent): Boolean {
            val current = events.lastOrNull()?.revision ?: 0L
            if (current != expectedRevision) return false
            events += event
            return true
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-11T12:00:00Z")
    }
}
