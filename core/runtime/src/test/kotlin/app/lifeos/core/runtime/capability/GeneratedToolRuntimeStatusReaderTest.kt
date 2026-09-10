package app.lifeos.core.runtime.capability

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class GeneratedToolRuntimeStatusReaderTest {
    @Test
    fun `projects deterministic durable tool status and trial statistics`() = runTest {
        val trialState = persistentState(
            toolId = "tool-z",
            finalState = GeneratedToolState.TRIAL,
            trialResults = listOf(
                GeneratedToolTrialResult(
                    invocationId = "inv-2",
                    success = false,
                    producedExpectedOutput = false,
                    safetyViolation = true,
                    latencyMs = 30,
                    recordedAt = Instant.parse("2026-09-10T12:00:02Z"),
                ),
                GeneratedToolTrialResult(
                    invocationId = "inv-1",
                    success = true,
                    producedExpectedOutput = true,
                    latencyMs = 10,
                    recordedAt = Instant.parse("2026-09-10T12:00:01Z"),
                ),
            ),
        )
        val quarantined = persistentState(
            toolId = "tool-a",
            finalState = GeneratedToolState.QUARANTINED,
        )
        val repository = StaticStateRepository(listOf(trialState, quarantined))

        val status = GeneratedToolRuntimeStatusReader(repository).snapshot()

        assertEquals(listOf("tool-a", "tool-z"), status.tools.map { it.toolId })
        assertEquals(2, status.totalTools)
        assertEquals(1, status.trialTools)
        assertEquals(1, status.quarantinedTools)
        assertEquals(0, status.activeTools)
        assertEquals(2, status.totalTrials)
        assertEquals(1, status.totalSafetyViolations)

        val trial = status.tools.single { it.toolId == "tool-z" }
        assertEquals("text.normalize", trial.capabilityId)
        assertEquals(2, trial.trials)
        assertEquals(1, trial.successes)
        assertEquals(1, trial.expectedOutputs)
        assertEquals(1, trial.safetyViolations)
        assertEquals(20.0, trial.averageLatencyMs)
        assertEquals(null, trial.promotionEvidenceId)
    }

    private suspend fun persistentState(
        toolId: String,
        finalState: GeneratedToolState,
        trialResults: List<GeneratedToolTrialResult> = emptyList(),
    ): GeneratedToolPersistentState {
        val now = Instant.parse("2026-09-10T12:00:00Z")
        val tools = GeneratedToolRegistry(now = { now })
        tools.register(record(toolId))
        tools.transition(toolId, GeneratedToolState.BUILT)
        tools.transition(toolId, GeneratedToolState.TESTED)
        tools.transition(toolId, GeneratedToolState.VERIFIED, confidence = 0.95)
        tools.transition(toolId, GeneratedToolState.TRIAL)
        if (finalState == GeneratedToolState.QUARANTINED) {
            tools.transition(toolId, GeneratedToolState.QUARANTINED, message = "test-quarantine")
        } else {
            require(finalState == GeneratedToolState.TRIAL)
        }
        val current = requireNotNull(tools.get(toolId))
        return GeneratedToolPersistentState(
            record = current,
            auditEntries = tools.auditSnapshot(toolId),
            trialEvidence = GeneratedToolTrialEvidence(toolId, trialResults),
        )
    }

    private fun record(toolId: String) = GeneratedToolRecord(
        manifest = GeneratedToolManifest(
            toolId = toolId,
            sourceCapability = CapabilityId("text.normalize"),
            sourceHash = "source-$toolId",
            buildHash = null,
            permissions = emptySet(),
            generatedAt = Instant.parse("2026-09-10T12:00:00Z"),
            requiredInputs = setOf("text"),
            requiredOutputs = setOf("normalized-text"),
        ),
        state = GeneratedToolState.GENERATED,
    )

    private class StaticStateRepository(
        private val states: List<GeneratedToolPersistentState>,
    ) : GeneratedToolStateRepository {
        override suspend fun loadAll(): List<GeneratedToolPersistentState> = states

        override suspend fun persistLifecycle(
            record: GeneratedToolRecord,
            auditEntries: List<GeneratedToolAuditEntry>,
            promotionEvidence: GeneratedToolPromotionEvidence?,
        ) = error("read-only test repository")

        override suspend fun persistTrialEvidence(evidence: GeneratedToolTrialEvidence) =
            error("read-only test repository")
    }
}
