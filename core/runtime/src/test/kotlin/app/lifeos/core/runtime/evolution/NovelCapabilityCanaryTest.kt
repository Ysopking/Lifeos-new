package app.lifeos.core.runtime.evolution

import app.lifeos.core.runtime.capability.CapabilityContract
import app.lifeos.core.runtime.capability.CapabilityDescriptor
import app.lifeos.core.runtime.capability.CapabilityGapDetector
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityRegistry
import app.lifeos.core.runtime.capability.CapabilityRequirement
import app.lifeos.core.runtime.capability.GeneratedToolArtifact
import app.lifeos.core.runtime.capability.GeneratedToolArtifactRepository
import app.lifeos.core.runtime.capability.GeneratedToolInstruction
import app.lifeos.core.runtime.capability.GeneratedToolLifecycleCoordinator
import app.lifeos.core.runtime.capability.GeneratedToolManifest
import app.lifeos.core.runtime.capability.GeneratedToolOpcode
import app.lifeos.core.runtime.capability.GeneratedToolProgram
import app.lifeos.core.runtime.capability.GeneratedToolProgramCodec
import app.lifeos.core.runtime.capability.GeneratedToolRecord
import app.lifeos.core.runtime.capability.GeneratedToolRegistry
import app.lifeos.core.runtime.capability.GeneratedToolState
import app.lifeos.core.runtime.capability.GeneratedToolTrialExecutionResult
import app.lifeos.core.runtime.capability.GeneratedToolTrialInvocation
import app.lifeos.core.runtime.capability.GeneratedToolTrialLedger
import app.lifeos.core.runtime.capability.GeneratedToolTrialRunner
import app.lifeos.core.runtime.capability.ProviderState
import app.lifeos.core.runtime.capability.ProviderType
import app.lifeos.core.runtime.capability.TrustLevel
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NovelCapabilityCanaryTest {
    private val t0 = Instant.parse("2026-09-11T03:00:00Z")

    @Test
    fun `novel canary is separate from initial trials and reaches non activating readiness`() = runTest {
        val fixture = fixture(initialTrials = 3)

        repeat(5) { index ->
            val result = fixture.canary.execute(
                fixture.subject,
                fixture.admission,
                invocation("canary-${index + 1}", "value ${index + 1}", "VALUE ${index + 1}"),
            )
            val executed = assertIs<NovelCapabilityCanaryExecutionResult.Executed>(result)
            assertFalse(executed.duplicate)
            assertEquals(null, executed.killSwitch)
        }

        val readiness = fixture.readiness.evaluate(fixture.subject, fixture.admission)

        assertEquals(NovelCapabilityCanaryReadinessDecision.READY_FOR_REVIEW, readiness.decision)
        assertEquals(5, readiness.completedOutcomes)
        assertEquals(5, readiness.reservedInvocations)
        assertEquals(5, readiness.successes)
        assertEquals(5, readiness.expectedOutputs)
        assertEquals(0, readiness.safetyViolations)
        assertFalse(readiness.activationAllowed)
        assertEquals(8, fixture.ledger.stats(TOOL_ID).trials)
        assertEquals(GeneratedToolState.TRIAL, fixture.tools.get(TOOL_ID)?.state)
        assertTrue(fixture.capabilities.providersFor(CAPABILITY).isEmpty())
    }

    @Test
    fun `duplicate canary invocation is idempotent and does not add a second trial`() = runTest {
        val fixture = fixture()
        val invocation = invocation("same-call", "hello", "HELLO")

        val first = assertIs<NovelCapabilityCanaryExecutionResult.Executed>(
            fixture.canary.execute(fixture.subject, fixture.admission, invocation)
        )
        val second = assertIs<NovelCapabilityCanaryExecutionResult.Executed>(
            fixture.canary.execute(fixture.subject, fixture.admission, invocation)
        )

        assertFalse(first.duplicate)
        assertTrue(second.duplicate)
        assertEquals(first.outcome, second.outcome)
        assertEquals(1, fixture.ledger.stats(TOOL_ID).trials)
        assertEquals(1, fixture.store.novelOutcomes(fixture.admission.id).size)
    }

    @Test
    fun `canary never requests permissions or productive effects`() = runTest {
        val fixture = fixture()
        val invocation = GeneratedToolTrialInvocation(
            toolId = TOOL_ID,
            invocationId = "permission-call",
            input = "hello",
            expectedOutput = "HELLO",
            requestedPermissions = setOf(app.lifeos.core.runtime.capability.ToolPermission.READ_MEMORY),
        )

        assertFailsWith<IllegalArgumentException> {
            fixture.canary.execute(fixture.subject, fixture.admission, invocation)
        }
        assertEquals(0, fixture.ledger.stats(TOOL_ID).trials)
    }

    @Test
    fun `provider appearing after admission invalidates novel evidence`() = runTest {
        val fixture = fixture()
        fixture.capabilities.register(
            CapabilityDescriptor(
                capabilityId = CAPABILITY,
                providerId = "late-provider",
                providerType = ProviderType.MODULE,
                contract = CapabilityContract(setOf("text"), setOf("upper-text")),
                state = ProviderState.ACTIVE,
                trustLevel = TrustLevel.HIGH,
            )
        )

        assertFailsWith<IllegalArgumentException> {
            fixture.gate.validate(fixture.admission, fixture.subject)
        }
        assertEquals(GeneratedToolState.TRIAL, fixture.tools.get(TOOL_ID)?.state)
    }

    @Test
    fun `wrong expected output produces not ready evidence without activation`() = runTest {
        val fixture = fixture()
        repeat(5) { index ->
            fixture.canary.execute(
                fixture.subject,
                fixture.admission,
                invocation("wrong-${index + 1}", "hello", "not-the-output"),
            )
        }

        val readiness = fixture.readiness.evaluate(fixture.subject, fixture.admission)

        assertEquals(NovelCapabilityCanaryReadinessDecision.NOT_READY, readiness.decision)
        assertTrue(readiness.reasons.any { it.startsWith("expected-output-rate:") })
        assertFalse(readiness.activationAllowed)
        assertEquals(GeneratedToolState.TRIAL, fixture.tools.get(TOOL_ID)?.state)
    }

    @Test
    fun `safety outcome atomically trips novel kill switch`() = runTest {
        val store = InMemoryNovelCapabilityCanaryStore()
        val request = NovelCapabilityCanaryReservationRequest(
            admissionEvidenceId = "admission",
            toolId = TOOL_ID,
            candidateRecordFingerprint = "record",
            invocationId = "unsafe-call",
            inputFingerprint = "input",
            expectedOutputFingerprint = "expected",
            maxInvocations = 5,
            reservedAt = t0,
        )
        val reservation = assertIs<NovelCapabilityCanaryReserveResult.Reserved>(store.reserveNovel(request)).reservation
        val outcome = NovelCapabilityCanaryOutcome(
            admissionEvidenceId = "admission",
            reservationId = reservation.id,
            toolId = TOOL_ID,
            candidateRecordFingerprint = "record",
            invocationId = "unsafe-call",
            trialResultFingerprint = "trial",
            success = false,
            producedExpectedOutput = false,
            safetyViolation = true,
            latencyMs = 1,
            recordedAt = t0.plusSeconds(1),
        )

        val recorded = assertIs<NovelCapabilityCanaryOutcomeWriteResult.Recorded>(store.recordNovelOutcome(outcome))

        assertEquals(NovelCapabilityCanaryStopReason.SAFETY_VIOLATION, recorded.killSwitch?.reason)
        assertEquals(recorded.killSwitch, store.novelKillSwitch("admission"))
        val stopped = assertIs<NovelCapabilityCanaryReserveResult.Stopped>(
            store.reserveNovel(request.copy(invocationId = "after-stop"))
        )
        assertEquals(recorded.killSwitch, stopped.evidence)
    }

    private suspend fun fixture(initialTrials: Int = 0): Fixture {
        val program = GeneratedToolProgram(
            toolId = TOOL_ID,
            capabilityId = CAPABILITY,
            requiredInputs = setOf("text"),
            requiredOutputs = setOf("upper-text"),
            instructions = listOf(GeneratedToolInstruction(GeneratedToolOpcode.UPPERCASE)),
        )
        val artifact = GeneratedToolArtifact.create(
            toolId = TOOL_ID,
            canonicalProgram = GeneratedToolProgramCodec.encode(program),
            createdAt = t0,
        )
        val artifactRepository = MemoryArtifactRepository(artifact)
        val tools = GeneratedToolRegistry(now = { t0 })
        val record = GeneratedToolRecord(
            manifest = GeneratedToolManifest(
                toolId = TOOL_ID,
                sourceCapability = CAPABILITY,
                sourceHash = artifact.sourceHash,
                buildHash = artifact.buildHash,
                permissions = emptySet(),
                generatedAt = t0,
                requiredInputs = setOf("text"),
                requiredOutputs = setOf("upper-text"),
            ),
            state = GeneratedToolState.GENERATED,
        )
        tools.register(record)
        tools.transition(TOOL_ID, GeneratedToolState.BUILT, message = "built")
        tools.transition(TOOL_ID, GeneratedToolState.TESTED, message = "tested")
        tools.transition(TOOL_ID, GeneratedToolState.VERIFIED, confidence = 0.95, message = "verified")
        tools.transition(TOOL_ID, GeneratedToolState.TRIAL, message = "trial")

        val ledger = GeneratedToolTrialLedger()
        val lifecycle = GeneratedToolLifecycleCoordinator(tools = tools, trialLedger = ledger)
        val runner = GeneratedToolTrialRunner(
            tools = tools,
            lifecycle = lifecycle,
            artifacts = artifactRepository,
            now = { t0.plusSeconds(10) },
            nanoTime = { 1_000_000L },
        )
        repeat(initialTrials) { index ->
            assertIs<GeneratedToolTrialExecutionResult.Completed>(
                runner.execute(invocation("initial-${index + 1}", "seed ${index + 1}", "SEED ${index + 1}"))
            )
        }

        val capabilities = CapabilityRegistry()
        val requirement = CapabilityRequirement(
            capabilityId = CAPABILITY,
            requiredInputs = setOf("text"),
            requiredOutputs = setOf("upper-text"),
        )
        val gap = requireNotNull(CapabilityGapDetector(capabilities).detect(requirement))
        val current = requireNotNull(tools.get(TOOL_ID))
        val subject = NovelCapabilityAdmissionSubject.create(gap, current, artifact)
        val gate = NovelCapabilityAdmissionGate(
            capabilities = capabilities,
            tools = tools,
            artifacts = artifactRepository,
            now = { t0.plusSeconds(20) },
        )
        val admission = gate.evaluate(subject)
        val store = InMemoryNovelCapabilityCanaryStore()
        val canary = NovelCapabilityCanaryCoordinator(
            admissionGate = gate,
            trialRunner = runner,
            trialLedger = ledger,
            store = store,
            now = { t0.plusSeconds(30) },
        )
        val readiness = NovelCapabilityCanaryReadinessGate(gate, ledger, store)
        return Fixture(
            tools,
            ledger,
            capabilities,
            gate,
            subject,
            admission,
            store,
            canary,
            readiness,
        )
    }

    private fun invocation(id: String, input: String, expected: String) = GeneratedToolTrialInvocation(
        toolId = TOOL_ID,
        invocationId = id,
        input = input,
        expectedOutput = expected,
    )

    private data class Fixture(
        val tools: GeneratedToolRegistry,
        val ledger: GeneratedToolTrialLedger,
        val capabilities: CapabilityRegistry,
        val gate: NovelCapabilityAdmissionGate,
        val subject: NovelCapabilityAdmissionSubject,
        val admission: NovelCapabilityAdmissionEvidence,
        val store: InMemoryNovelCapabilityCanaryStore,
        val canary: NovelCapabilityCanaryCoordinator,
        val readiness: NovelCapabilityCanaryReadinessGate,
    )

    private class MemoryArtifactRepository(artifact: GeneratedToolArtifact) : GeneratedToolArtifactRepository {
        private val artifact = artifact

        override suspend fun persist(artifact: GeneratedToolArtifact) = error("test repository is read-only")
        override suspend fun load(toolId: String): GeneratedToolArtifact? = artifact.takeIf { it.toolId == toolId }
        override suspend fun loadAll(): List<GeneratedToolArtifact> = listOf(artifact)
    }

    private companion object {
        val CAPABILITY = CapabilityId("text.uppercase.local")
        const val TOOL_ID = "tool-novel-uppercase"
    }
}
