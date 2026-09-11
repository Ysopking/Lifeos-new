package app.lifeos.core.runtime.capability

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PrivateGeneratedToolTrialSuiteTest {
    @Test
    fun `suite records three independent expected trials without activation`() = runTest {
        val fixture = fixture(artifactAvailable = true)

        val result = fixture.suite.execute(fixture.record)

        assertEquals(3, result.executions.size)
        assertTrue(result.completeAndExpected)
        val stats = assertNotNull(result.finalStats)
        assertEquals(3, stats.trials)
        assertEquals(3, stats.successes)
        assertEquals(3, stats.expectedOutputs)
        assertEquals(0, stats.safetyViolations)
        assertEquals(GeneratedToolState.TRIAL, fixture.tools.get(TOOL_ID)?.state)
        assertTrue(fixture.lifecycle.evaluatePromotion(TOOL_ID) is GeneratedToolPromotionEvaluation.Eligible)
    }

    @Test
    fun `missing artifact is converted into quarantine evidence and suite stops`() = runTest {
        val fixture = fixture(artifactAvailable = false)

        val result = fixture.suite.execute(fixture.record)

        assertEquals(1, result.executions.size)
        assertFalse(result.completeAndExpected)
        assertEquals("bounded-trial-cases-unavailable", result.suiteFailure)
        val stats = assertNotNull(result.finalStats)
        assertEquals(1, stats.trials)
        assertEquals(1, stats.safetyViolations)
        assertEquals(GeneratedToolState.QUARANTINED, fixture.tools.get(TOOL_ID)?.state)
    }

    private suspend fun fixture(artifactAvailable: Boolean): Fixture {
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
        val repository = MemoryArtifactRepository(if (artifactAvailable) artifact else null)
        val tools = GeneratedToolRegistry(now = { createdAt })
        tools.register(
            GeneratedToolRecord(
                manifest = GeneratedToolManifest(
                    toolId = TOOL_ID,
                    sourceCapability = CapabilityId("text.uppercase"),
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
        val admission = lifecycle.admitToTrial(TOOL_ID)
        assertTrue(admission is GeneratedToolTrialAdmissionResult.TrialStarted)
        val record = (admission as GeneratedToolTrialAdmissionResult.TrialStarted).record
        var tick = 0L
        val runner = GeneratedToolTrialRunner(
            tools = tools,
            lifecycle = lifecycle,
            artifacts = repository,
            now = { createdAt },
            nanoTime = {
                tick += 1_000_000L
                tick
            },
        )
        return Fixture(
            record = record,
            tools = tools,
            lifecycle = lifecycle,
            suite = PrivateGeneratedToolTrialSuite(runner, repository),
        )
    }

    private data class Fixture(
        val record: GeneratedToolRecord,
        val tools: GeneratedToolRegistry,
        val lifecycle: GeneratedToolLifecycleCoordinator,
        val suite: PrivateGeneratedToolTrialSuite,
    )

    private class MemoryArtifactRepository(
        private val artifact: GeneratedToolArtifact?,
    ) : GeneratedToolArtifactRepository {
        override suspend fun persist(artifact: GeneratedToolArtifact) = error("read-only test artifact repository")
        override suspend fun load(toolId: String): GeneratedToolArtifact? = artifact
        override suspend fun loadAll(): List<GeneratedToolArtifact> = listOfNotNull(artifact)
    }

    private companion object {
        const val TOOL_ID = "generated-uppercase-suite"
    }
}
