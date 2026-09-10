package app.lifeos.core.runtime.capability

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest

class GeneratedToolRuntimeStatusReaderTest {
    @Test
    fun `projects deterministic tool status and trial statistics without mutable access`() = runTest {
        val tools = GeneratedToolRegistry()
        val ledger = GeneratedToolTrialLedger()
        val capabilities = CapabilityRegistry()
        tools.register(record("tool-z", GeneratedToolState.TRIAL))
        tools.register(record("tool-a", GeneratedToolState.QUARANTINED))

        ledger.record(
            "tool-z",
            GeneratedToolTrialResult(
                invocationId = "inv-2",
                success = false,
                producedExpectedOutput = false,
                safetyViolation = true,
                latencyMs = 30,
                recordedAt = Instant.parse("2026-09-10T12:00:02Z"),
            ),
        )
        ledger.record(
            "tool-z",
            GeneratedToolTrialResult(
                invocationId = "inv-1",
                success = true,
                producedExpectedOutput = true,
                latencyMs = 10,
                recordedAt = Instant.parse("2026-09-10T12:00:01Z"),
            ),
        )

        val status = GeneratedToolRuntimeStatusReader(tools, ledger, capabilities).snapshot()

        assertEquals(listOf("tool-a", "tool-z"), status.tools.map { it.toolId })
        assertEquals(2, status.totalTools)
        assertEquals(1, status.trialTools)
        assertEquals(1, status.quarantinedTools)
        assertEquals(0, status.activeTools)
        assertEquals(2, status.totalTrials)
        assertEquals(1, status.totalSafetyViolations)
        assertEquals(emptySet(), status.generatedProviderIds)

        val trial = status.tools.single { it.toolId == "tool-z" }
        assertEquals("text.normalize", trial.capabilityId)
        assertEquals(2, trial.trials)
        assertEquals(1, trial.successes)
        assertEquals(1, trial.expectedOutputs)
        assertEquals(1, trial.safetyViolations)
        assertEquals(20.0, trial.averageLatencyMs)
        assertFalse(trial.activeProviderRegistered)
    }

    private fun record(toolId: String, state: GeneratedToolState) = GeneratedToolRecord(
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
        state = state,
        verificationConfidence = 0.95,
    )
}
