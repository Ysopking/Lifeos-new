package app.lifeos.core.runtime.trace

import app.lifeos.core.runtime.health.HealthNodeId
import app.lifeos.core.runtime.health.RecoveryAction
import app.lifeos.core.runtime.health.RecoveryActionResult
import app.lifeos.core.runtime.health.RecoveryPlan
import app.lifeos.core.runtime.health.RepairProbeObservation
import app.lifeos.core.runtime.health.RepairProbeStatus
import app.lifeos.core.runtime.health.RuntimeRepairProbe
import app.lifeos.core.runtime.health.SelfHealingIncidentId
import app.lifeos.core.runtime.health.SelfHealingIncidentSnapshot
import app.lifeos.core.runtime.health.SelfHealingIncidentState
import app.lifeos.core.runtime.health.selfHealingFingerprint
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SelfHealingDecisionTraceRecorderTest {
    @Test
    fun `recovered incident retains attempted action and verification evidence idempotently`() = runTest {
        val ledger = DecisionTraceLedger(MemoryRepository())
        val recorder = SelfHealingDecisionTraceRecorder(ledger)
        val plan = plan()
        val snapshot = snapshot(
            plan = plan,
            state = SelfHealingIncidentState.RECOVERED,
            revision = 4L,
            attemptedActionIds = listOf(ACTION_ID),
            detail = "recovery-verified",
            evidence = "runtime-probe:healthy",
        )

        val first = assertIs<DecisionTraceRecordResult.Recorded>(recorder.record(plan, snapshot)).trace
        val replay = assertIs<DecisionTraceRecordResult.Recorded>(recorder.record(plan, snapshot)).trace

        assertEquals(first.revision, replay.revision)
        assertTrue(replay.nodes.any {
            it.sourceType == "self-healing-incident-state" &&
                it.type == DecisionTraceNodeType.RECOVERY_OUTCOME &&
                "STATE_RECOVERED" in it.reasonCodes
        })
        assertTrue(replay.nodes.any {
            it.sourceType == "self-healing-recovery-action" && it.displayLabel == ACTION_ID
        })
        assertTrue(replay.nodes.any {
            it.sourceType == "self-healing-verification-evidence" &&
                "VERIFICATION_EVIDENCE" in it.reasonCodes
        })
        assertTrue(replay.links.any { it.type == DecisionTraceLinkType.RECOVERED_BY })
        assertTrue(replay.links.any { it.type == DecisionTraceLinkType.SUPPORTS })
    }

    @Test
    fun `budget blocked incident exposes rejection and resource constraint`() = runTest {
        val ledger = DecisionTraceLedger(MemoryRepository())
        val recorder = SelfHealingDecisionTraceRecorder(ledger)
        val plan = plan()
        val snapshot = snapshot(
            plan = plan,
            state = SelfHealingIncidentState.BLOCKED,
            revision = 2L,
            detail = "self-healing-world-budget:shared-world-budget-gate-not-installed",
        )

        val trace = assertIs<DecisionTraceRecordResult.Recorded>(recorder.record(plan, snapshot)).trace

        assertTrue(trace.nodes.any {
            it.sourceType == "self-healing-incident-state" && it.type == DecisionTraceNodeType.REJECTION
        })
        assertTrue(trace.nodes.any {
            it.sourceType == "self-healing-resource-block" &&
                it.type == DecisionTraceNodeType.RESOURCE_CONSTRAINT
        })
        assertTrue(trace.links.any { it.type == DecisionTraceLinkType.CONSTRAINS })
    }

    private fun plan(): RecoveryPlan {
        val nodeId = HealthNodeId("runtime:self-healing-trace-test")
        return RecoveryPlan(
            nodeId = nodeId,
            source = "self-healing-trace-test",
            actions = listOf(
                object : RecoveryAction {
                    override val id: String = ACTION_ID
                    override suspend fun execute(): RecoveryActionResult =
                        RecoveryActionResult.Success("unused-by-trace-test")
                }
            ),
            verificationProbes = listOf(
                RuntimeRepairProbe("runtime-probe", nodeId) {
                    RepairProbeObservation(RepairProbeStatus.HEALTHY)
                }
            ),
        )
    }

    private fun snapshot(
        plan: RecoveryPlan,
        state: SelfHealingIncidentState,
        revision: Long,
        attemptedActionIds: List<String> = emptyList(),
        detail: String? = null,
        evidence: String? = null,
    ): SelfHealingIncidentSnapshot {
        val fingerprint = plan.selfHealingFingerprint()
        return SelfHealingIncidentSnapshot(
            incidentId = SelfHealingIncidentId.create(plan.nodeId, "incident-fingerprint", fingerprint),
            nodeId = plan.nodeId,
            planFingerprint = fingerprint,
            state = state,
            nextActionIndex = attemptedActionIds.size,
            attemptedActionIds = attemptedActionIds,
            lastDetail = detail,
            lastEvidenceSummary = evidence,
            ledgerRevision = revision,
            lastRecordedAt = NOW.plusSeconds(revision),
        )
    }

    private class MemoryRepository : DecisionTraceRepository {
        private val traces = mutableListOf<DecisionTrace>()

        override suspend fun loadReport() = DecisionTraceRepositoryLoadReport(traces.toList())

        override suspend fun save(expectedRevision: Long, trace: DecisionTrace): Boolean {
            val current = traces.filter { it.id == trace.id }.maxOfOrNull { it.revision } ?: 0L
            if (current != expectedRevision) return false
            require(trace.revision == expectedRevision + 1L)
            traces += trace
            return true
        }
    }

    private companion object {
        const val ACTION_ID = "restart-runtime"
        val NOW: Instant = Instant.parse("2026-09-13T18:00:00Z")
    }
}
