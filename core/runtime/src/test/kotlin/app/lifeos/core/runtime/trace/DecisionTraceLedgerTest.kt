package app.lifeos.core.runtime.trace

import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DecisionTraceLedgerTest {
    @Test
    fun `identity is exact case-sensitive and stable`() {
        val first = DecisionTraceId.create("goal", "Goal-7")
        assertEquals(first, DecisionTraceId.create("goal", "Goal-7"))
        assertNotEquals(first, DecisionTraceId.create("goal", "goal-7"))
    }

    @Test
    fun `restart reconstructs identical graph and changed revision changes node`() = runTest {
        val repository = MemoryRepository()
        val id = DecisionTraceId.create("goal", "goal-7")
        val factV1 = node(DecisionTraceNodeType.OBSERVED_FACT, "goal", "goal-7", 1)
        val selected = node(DecisionTraceNodeType.SELECTION, "convergence", "decision-4", 3)
        val first = DecisionTraceLedger(repository).append(
            id, listOf(factV1, selected),
            listOf(DecisionTraceLink(factV1.id, selected.id, DecisionTraceLinkType.SUPPORTS)),
        )
        assertEquals(first, DecisionTraceLedger(repository).snapshot(id))
        assertNotEquals(factV1.id, node(DecisionTraceNodeType.OBSERVED_FACT, "goal", "goal-7", 2).id)
    }

    @Test
    fun `blocked alternatives and unresolved uncertainty remain visible`() = runTest {
        val repository = MemoryRepository()
        val id = DecisionTraceId.create("goal", "goal-blocked")
        val alternative = node(DecisionTraceNodeType.CANDIDATE_ALTERNATIVE, "provider", "local", 1)
        val rejection = DecisionTraceNode.create(
            DecisionTraceNodeType.REJECTION, "owner-policy", "owner-policy-decision:7", 7,
            listOf("EFFECT_NOT_GRANTED", "RESOURCE_NOT_GRANTED"), recordedAt = NOW,
        )
        val uncertainty = node(DecisionTraceNodeType.UNRESOLVED_UNCERTAINTY, "convergence", "unresolved-2", 2)
        val trace = DecisionTraceLedger(repository).append(
            id, listOf(alternative, rejection, uncertainty),
            listOf(DecisionTraceLink(alternative.id, rejection.id, DecisionTraceLinkType.REJECTED_BY)),
        )
        val projection = DecisionTraceProjector().project(trace)
        assertTrue(projection.unresolved)
        assertEquals(listOf("EFFECT_NOT_GRANTED", "RESOURCE_NOT_GRANTED"), rejection.reasonCodes)
        assertTrue(projection.alternatives.contains(rejection))
        assertTrue(projection.outcomes.isEmpty())
    }

    @Test
    fun `corrupt storage fails closed without mutation`() = runTest {
        val repository = MemoryRepository(unreadable = true)
        assertFailsWith<IllegalStateException> {
            DecisionTraceLedger(repository).snapshot(DecisionTraceId.create("goal", "goal-corrupt"))
        }
        assertEquals(0, repository.saveCalls)
    }

    private fun node(type: DecisionTraceNodeType, sourceType: String, sourceId: String, revision: Long) =
        DecisionTraceNode.create(type, sourceType, sourceId, revision, recordedAt = NOW)

    private class MemoryRepository(private val unreadable: Boolean = false) : DecisionTraceRepository {
        private val traces = mutableListOf<DecisionTrace>()
        var saveCalls = 0
        override suspend fun loadReport() = DecisionTraceRepositoryLoadReport(
            traces.toList(), if (unreadable) listOf("corrupt.trace") else emptyList(),
        )
        override suspend fun save(expectedRevision: Long, trace: DecisionTrace): Boolean {
            saveCalls += 1
            val current = traces.filter { it.id == trace.id }.maxOfOrNull { it.revision } ?: 0L
            if (current != expectedRevision) return false
            require(trace.revision == expectedRevision + 1)
            traces += trace
            return true
        }
    }

    private companion object { val NOW: Instant = Instant.parse("2026-09-11T20:00:00Z") }
}
