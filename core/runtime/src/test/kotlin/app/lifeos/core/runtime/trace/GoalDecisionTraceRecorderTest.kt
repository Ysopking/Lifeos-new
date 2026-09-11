package app.lifeos.core.runtime.trace

import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.policy.OwnerPolicyAssessment
import app.lifeos.core.runtime.policy.OwnerPolicyDecisionId
import app.lifeos.core.runtime.policy.OwnerPolicyEvaluationMode
import app.lifeos.core.runtime.policy.OwnerPolicyReasonCode
import app.lifeos.core.runtime.resource.ResourceBudgetAccountId
import app.lifeos.core.runtime.resource.ResourceBudgetReservation
import app.lifeos.core.runtime.resource.ResourceBudgetReservationState
import app.lifeos.core.runtime.resource.ResourceBudgetUsage
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GoalDecisionTraceRecorderTest {
    @Test
    fun `policy reservation and settlement append to one exact goal trace`() = runTest {
        val repository = MemoryRepository()
        val ledger = DecisionTraceLedger(repository)
        val recorder = GoalDecisionTraceRecorder(ledger)
        val goalId = PhotonId("Goal-Trace-7")
        val policy = blockedPolicyAssessment()

        val first = assertIs<DecisionTraceRecordResult.Recorded>(
            recorder.recordOwnerPolicy(goalId, 3L, NOW, policy)
        ).trace
        val replay = assertIs<DecisionTraceRecordResult.Recorded>(
            recorder.recordOwnerPolicy(goalId, 3L, NOW, policy)
        ).trace
        assertEquals(first.revision, replay.revision)

        val reservation = ResourceBudgetReservation.create(
            accountId = ResourceBudgetAccountId("goal-action:${goalId.value}"),
            idempotencyKey = "goal-action:${goalId.value}:QUERY:policy-4",
            usage = ResourceBudgetUsage(workUnits = 8L),
            createdAt = NOW.plusSeconds(1),
        )
        recorder.recordResourceReservation(goalId, 3L, NOW, reservation)
        val committed = reservation.copy(
            state = ResourceBudgetReservationState.COMMITTED,
            settledUsage = ResourceBudgetUsage(workUnits = 5L),
            settledAt = NOW.plusSeconds(2),
        )
        recorder.recordResourceReservation(goalId, 3L, NOW, committed)

        val trace = requireNotNull(ledger.snapshot(DecisionTraceId.create("goal-photon", goalId.value)))
        assertTrue(trace.nodes.any { it.sourceId == policy.decisionId.value })
        val resourceNodes = trace.nodes.filter { it.sourceId == reservation.id.value }
        assertEquals(setOf(1L, 2L), resourceNodes.map { it.sourceRevision }.toSet())
        assertEquals(
            setOf("RESERVED", "COMMITTED"),
            resourceNodes.flatMap { it.reasonCodes }.toSet(),
        )
        assertEquals(NOW.plusSeconds(1), resourceNodes.single { "RESERVED" in it.reasonCodes }.recordedAt)
        assertEquals(NOW.plusSeconds(2), resourceNodes.single { "COMMITTED" in it.reasonCodes }.recordedAt)
    }

    @Test
    fun `unreadable trace repository reports unavailable without mutation`() = runTest {
        val repository = MemoryRepository(unreadable = true)
        val recorder = GoalDecisionTraceRecorder(DecisionTraceLedger(repository))

        val result = recorder.recordOwnerPolicy(
            goalPhotonId = PhotonId("goal-corrupt-trace"),
            goalPhotonRevision = 1L,
            recordedAt = NOW,
            assessment = blockedPolicyAssessment(),
        )

        assertIs<DecisionTraceRecordResult.Unavailable>(result)
        assertEquals(0, repository.saveCalls)
    }

    private fun blockedPolicyAssessment() = OwnerPolicyAssessment(
        decisionId = OwnerPolicyDecisionId("owner-policy-decision:${"a".repeat(64)}"),
        policyRevision = 4L,
        mode = OwnerPolicyEvaluationMode.LIVE,
        requestFingerprint = "b".repeat(64),
        allowed = false,
        reasonCodes = listOf(OwnerPolicyReasonCode.EFFECT_NOT_GRANTED),
        reasons = listOf("effect-not-granted:REMINDER"),
    )

    private class MemoryRepository(
        private val unreadable: Boolean = false,
    ) : DecisionTraceRepository {
        private val traces = mutableListOf<DecisionTrace>()
        var saveCalls = 0

        override suspend fun loadReport(): DecisionTraceRepositoryLoadReport =
            DecisionTraceRepositoryLoadReport(
                traces = traces.toList(),
                unreadableEntries = if (unreadable) listOf("trace-corrupt") else emptyList(),
            )

        override suspend fun save(expectedRevision: Long, trace: DecisionTrace): Boolean {
            saveCalls += 1
            val current = traces.filter { it.id == trace.id }.maxOfOrNull { it.revision } ?: 0L
            if (current != expectedRevision) return false
            require(trace.revision == expectedRevision + 1L)
            traces += trace
            return true
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-11T22:00:00Z")
    }
}
