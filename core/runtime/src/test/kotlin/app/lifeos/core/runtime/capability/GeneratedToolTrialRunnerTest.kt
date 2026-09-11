package app.lifeos.core.runtime.capability

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class GeneratedToolTrialRunnerTest {
    @Test
    fun `executes exact durable artifact and records expected trial result`() = runTest {
        val fixture = fixture()

        val result = fixture.runner.execute(
            GeneratedToolTrialInvocation(
                toolId = TOOL_ID,
                invocationId = "trial-1",
                input = "LifeOS",
                expectedOutput = "LIFEOS",
            )
        )

        assertTrue(result is GeneratedToolTrialExecutionResult.Completed)
        assertEquals("LIFEOS", result.output)
        val recorded = result.trial
        assertTrue(recorded is GeneratedToolTrialRecordResult.Recorded)
        assertEquals(1, recorded.stats.trials)
        assertEquals(1, recorded.stats.successes)
        assertEquals(1, recorded.stats.expectedOutputs)
        assertEquals(0, recorded.stats.safetyViolations)
        assertEquals(GeneratedToolState.TRIAL, fixture.tools.get(TOOL_ID)?.state)
    }

    @Test
    fun `missing executable artifact is safety evidence and quarantines before execution`() = runTest {
        val fixture = fixture(artifactAvailable = false)

        val result = fixture.runner.execute(
            GeneratedToolTrialInvocation(
                toolId = TOOL_ID,
                invocationId = "trial-missing",
                input = "LifeOS",
                expectedOutput = "LIFEOS",
            )
        )

        assertTrue(result is GeneratedToolTrialExecutionResult.Blocked)
        assertEquals(listOf("artifact-missing"), result.reasons)
        val quarantine = result.trial
        assertNotNull(quarantine)
        assertTrue(quarantine is GeneratedToolTrialRecordResult.Quarantined)
        assertEquals(1, quarantine.stats.safetyViolations)
        assertEquals(GeneratedToolState.QUARANTINED, fixture.tools.get(TOOL_ID)?.state)
    }

    @Test
    fun `artifact contract drift is quarantined even when hashes still match`() = runTest {
        val fixture = fixture(recordCapability = CapabilityId("text.lowercase"))

        val result = fixture.runner.execute(
            GeneratedToolTrialInvocation(
                toolId = TOOL_ID,
                invocationId = "trial-contract-drift",
                input = "LifeOS",
                expectedOutput = "LIFEOS",
            )
        )

        assertTrue(result is GeneratedToolTrialExecutionResult.Blocked)
        assertTrue("program-capability-mismatch" in result.reasons)
        val quarantine = result.trial
        assertNotNull(quarantine)
        assertTrue(quarantine is GeneratedToolTrialRecordResult.Quarantined)
        assertEquals(1, quarantine.stats.safetyViolations)
        assertEquals(GeneratedToolState.QUARANTINED, fixture.tools.get(TOOL_ID)?.state)
    }

    @Test
    fun `sandbox permission denial quarantines without executing artifact`() = runTest {
        val fixture = fixture()

        val result = fixture.runner.execute(
            GeneratedToolTrialInvocation(
                toolId = TOOL_ID,
                invocationId = "trial-network",
                input = "LifeOS",
                expectedOutput = "LIFEOS",
                requestedPermissions = setOf(ToolPermission.NETWORK_ACCESS),
            )
        )

        assertTrue(result is GeneratedToolTrialExecutionResult.Blocked)
        assertTrue(result.reasons.any { it.startsWith("permissions-not-declared:") })
        assertEquals(null, result.trial)
        assertEquals(GeneratedToolState.QUARANTINED, fixture.tools.get(TOOL_ID)?.state)
    }

    private suspend fun fixture(
        artifactAvailable: Boolean = true,
        recordCapability: CapabilityId = CapabilityId("text.uppercase"),
    ): Fixture {
        val createdAt = Instant.parse("2026-09-11T00:00:00Z")
        val program = GeneratedToolProgram(
            toolId = TOOL_ID,
            capabilityId = CapabilityId("text.uppercase"),
            requiredInputs = setOf("text"),
            requiredOutputs = setOf("text"),
            instructions = listOf(GeneratedToolInstruction(GeneratedToolOpcode.UPPERCASE)),
        )
        val artifact = GeneratedToolArtifact.create(
            toolId = TOOL_ID,
            canonicalProgram = GeneratedToolProgramCodec.encode(program),
            createdAt = createdAt,
        )
        val tools = GeneratedToolRegistry(now = { createdAt })
        tools.register(
            GeneratedToolRecord(
                manifest = GeneratedToolManifest(
                    toolId = TOOL_ID,
                    sourceCapability = recordCapability,
                    sourceHash = artifact.sourceHash,
                    buildHash = artifact.buildHash,
                    permissions = emptySet(),
                    generatedAt = createdAt,
                    requiredInputs = setOf("text"),
                    requiredOutputs = setOf("text"),
                ),
                state = GeneratedToolState.GENERATED,
            )
        )
        tools.transition(TOOL_ID, GeneratedToolState.BUILT, message = "built")
        tools.transition(TOOL_ID, GeneratedToolState.TESTED, message = "tested")
        tools.transition(
            TOOL_ID,
            GeneratedToolState.VERIFIED,
            confidence = 0.95,
            message = "verified",
        )
        val ledger = GeneratedToolTrialLedger()
        val lifecycle = GeneratedToolLifecycleCoordinator(
            tools = tools,
            trialLedger = ledger,
        )
        assertTrue(lifecycle.admitToTrial(TOOL_ID) is GeneratedToolTrialAdmissionResult.TrialStarted)
        val repository = MemoryArtifactRepository(if (artifactAvailable) artifact else null)
        var tick = 0L
        return Fixture(
            tools = tools,
            runner = GeneratedToolTrialRunner(
                tools = tools,
                lifecycle = lifecycle,
                artifacts = repository,
                now = { createdAt },
                nanoTime = {
                    tick += 1_000_000L
                    tick
                },
            ),
        )
    }

    private data class Fixture(
        val tools: GeneratedToolRegistry,
        val runner: GeneratedToolTrialRunner,
    )

    private class MemoryArtifactRepository(
        private val artifact: GeneratedToolArtifact?,
    ) : GeneratedToolArtifactRepository {
        override suspend fun persist(artifact: GeneratedToolArtifact) = error("read-only test artifact repository")

        override suspend fun load(toolId: String): GeneratedToolArtifact? = artifact

        override suspend fun loadAll(): List<GeneratedToolArtifact> = listOfNotNull(artifact)
    }

    private companion object {
        const val TOOL_ID = "generated-uppercase"
    }
}
