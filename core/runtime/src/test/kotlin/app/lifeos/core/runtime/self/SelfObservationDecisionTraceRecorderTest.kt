package app.lifeos.core.runtime.self

import app.lifeos.core.field.world.WorldFieldVector
import app.lifeos.core.runtime.trace.DecisionTrace
import app.lifeos.core.runtime.trace.DecisionTraceNodeType
import app.lifeos.core.runtime.trace.DecisionTraceRepository
import app.lifeos.core.runtime.trace.DecisionTraceRepositoryLoadReport
import app.lifeos.core.runtime.trace.DecisionTraceLedger
import app.lifeos.core.runtime.world.SelfStateWorldBand
import app.lifeos.core.runtime.world.SelfStateWorldClassificationPolicy
import app.lifeos.core.runtime.world.SelfStateWorldFormulaAssessment
import app.lifeos.core.runtime.world.WorldFormulaExecution
import app.lifeos.core.runtime.world.WorldFormulaExecutionState
import app.lifeos.core.runtime.world.WorldFormulaStatus
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SelfObservationDecisionTraceRecorderTest {
    @Test
    fun repeatedIdenticalAssessmentDoesNotCreateTraceFlood() = runTest {
        val repository = InMemoryTraceRepository()
        val recorder = SelfObservationDecisionTraceRecorder(DecisionTraceLedger(repository))
        val snapshot = snapshot()
        val assessment = assessment(snapshot.authorityFingerprint)

        val first = recorder.record(snapshot, assessment)
        val second = recorder.record(snapshot.copy(capturedAt = snapshot.capturedAt.plusSeconds(5)), assessment)

        assertEquals(first.revision, second.revision)
        assertEquals(1, repository.traces.size)
        assertTrue(first.nodes.any { it.type == DecisionTraceNodeType.OBSERVED_FACT })
        assertTrue(first.nodes.any { it.type == DecisionTraceNodeType.EXECUTION_OUTCOME })
    }

    private fun assessment(authority: String) = SelfStateWorldFormulaAssessment(
        analysisId = "a".repeat(64),
        sourceFingerprint = "b".repeat(64),
        authorityFingerprint = authority,
        classificationPolicyFingerprint = SelfStateWorldClassificationPolicy.V1.fingerprint(),
        band = SelfStateWorldBand.DEGRADED,
        execution = WorldFormulaExecution(
            state = WorldFormulaExecutionState.INVALID,
            status = WorldFormulaStatus.INVALID_EQUATION,
            snapshot = null,
            persisted = false,
            message = "test",
        ),
        controllerVector = WorldFieldVector.EMPTY,
        reasonCodes = emptyList(),
    )

    private fun snapshot() = LifeOsSelfStateSnapshot(
        capturedAt = Instant.parse("2026-09-19T12:00:00Z"),
        photon = SelfPhotonState(1, 1, 0, "index"),
        memory = SelfMemoryState(1, 1, 0, "memory"),
        world = SelfWorldState(1, "world", 1, "eq-v1", "eq", "boot", "boot-fp", "cog"),
        runtime = SelfRuntimeState("topology", setOf("runtime"), setOf("runtime"), emptySet(), emptySet(), emptySet()),
        resource = SelfResourceState("hardware", 1.0, 1.0, 1.0, 1.0, 1.0),
        health = SelfHealthState(1, 0, 0, 0, 0, 0),
        recovery = SelfRecoveryState(emptySet(), "recovery"),
        tools = SelfToolState(0, 0, 0, 0, 0),
        liveSources = SelfLiveSourceState(0, 0, 0, 0, "sources"),
    )

    private class InMemoryTraceRepository : DecisionTraceRepository {
        val traces = mutableListOf<DecisionTrace>()

        override suspend fun loadReport(): DecisionTraceRepositoryLoadReport =
            DecisionTraceRepositoryLoadReport(traces.toList())

        override suspend fun save(expectedRevision: Long, trace: DecisionTrace): Boolean {
            val current = traces.filter { it.id == trace.id }.maxOfOrNull { it.revision } ?: 0L
            if (current != expectedRevision) return false
            traces += trace
            return true
        }
    }
}
