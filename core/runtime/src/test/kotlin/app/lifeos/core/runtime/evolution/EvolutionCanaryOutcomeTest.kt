package app.lifeos.core.runtime.evolution

import app.lifeos.core.runtime.buildstudio.BuildArtifactEvidence
import app.lifeos.core.runtime.buildstudio.BuildCapabilityChange
import app.lifeos.core.runtime.buildstudio.BuildCapabilityChangeType
import app.lifeos.core.runtime.buildstudio.BuildCommandResult
import app.lifeos.core.runtime.buildstudio.BuildDesignSpec
import app.lifeos.core.runtime.buildstudio.BuildGateCommand
import app.lifeos.core.runtime.buildstudio.BuildProvenance
import app.lifeos.core.runtime.buildstudio.BuildSpec
import app.lifeos.core.runtime.buildstudio.BuildStudioCandidate
import app.lifeos.core.runtime.buildstudio.BuildVerificationEvidence
import app.lifeos.core.runtime.buildstudio.BuildVerificationPolicy
import app.lifeos.core.runtime.buildstudio.CandidateArtifact
import app.lifeos.core.runtime.buildstudio.SourcePatchOperation
import app.lifeos.core.runtime.buildstudio.SourcePatchOperationType
import app.lifeos.core.runtime.buildstudio.SourcePatchPlan
import app.lifeos.core.runtime.capability.CapabilityContract
import app.lifeos.core.runtime.capability.CapabilityDescriptor
import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.capability.CapabilityGapType
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityRequirement
import app.lifeos.core.runtime.capability.GeneratedToolLifecycleCoordinator
import app.lifeos.core.runtime.capability.GeneratedToolManifest
import app.lifeos.core.runtime.capability.GeneratedToolPromotionEvaluation
import app.lifeos.core.runtime.capability.GeneratedToolRecord
import app.lifeos.core.runtime.capability.GeneratedToolRegistry
import app.lifeos.core.runtime.capability.GeneratedToolState
import app.lifeos.core.runtime.capability.ProviderState
import app.lifeos.core.runtime.capability.ProviderType
import app.lifeos.core.runtime.capability.TrustLevel
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EvolutionCanaryOutcomeTest {
    private val evidenceTime = Instant.parse("2026-09-10T12:00:00Z")

    @Test
    fun `clean canary outcomes become non activating promotion review readiness and J03 sees them`() = runBlocking {
        val fixture = fixture(maxInvocations = 10)
        val harness = harness(fixture)

        repeat(5) { index ->
            val route = reserve(harness, fixture, "clean-$index")
            val recorded = harness.coordinator.record(
                fixture.bundle,
                successfulInput(route, "clean-$index", latencyMs = 10),
            )
            assertFalse(recorded.duplicate)
            assertEquals(null, recorded.killSwitch)
        }

        val readiness = harness.readiness.evaluate(fixture.bundle)
        assertEquals(EvolutionCanaryReadinessDecision.READY_FOR_PROMOTION_REVIEW, readiness.decision)
        assertEquals(listOf("ready-for-explicit-promotion-review"), readiness.reasons)
        assertEquals(5, readiness.completedOutcomes)
        assertEquals(5, readiness.reservedInvocations)
        assertEquals(1.0, readiness.successRate)
        assertEquals(1.0, readiness.expectedOutputRate)
        assertFalse(readiness.activationAllowed)
        assertIs<GeneratedToolPromotionEvaluation.Eligible>(harness.lifecycle.evaluatePromotion(TOOL_ID))
    }

    @Test
    fun `hard failure trips kill switch quarantines tool and forces future baseline`() = runBlocking {
        val fixture = fixture(maxInvocations = 10)
        val harness = harness(fixture)
        val firstRoute = reserve(harness, fixture, "hard-1")

        val result = harness.coordinator.record(
            fixture.bundle,
            EvolutionCanaryOutcomeInput(
                reservationId = firstRoute.reservation.id,
                invocationId = "hard-1",
                success = false,
                producedExpectedOutput = false,
                latencyMs = 12,
                hardFailures = setOf(EvolutionHardFailure.SAFETY_VIOLATION),
            ),
        )

        val stop = assertNotNull(result.killSwitch)
        assertEquals(EvolutionCanaryStopReason.HARD_FAILURE, stop.reason)
        assertEquals(setOf(EvolutionHardFailure.SAFETY_VIOLATION), stop.hardFailures)
        assertEquals(GeneratedToolState.QUARANTINED, harness.registry.get(TOOL_ID)?.state)
        assertIs<GeneratedToolPromotionEvaluation.Blocked>(harness.lifecycle.evaluatePromotion(TOOL_ID))

        val laterKey = candidateAssignmentKey(harness.router, fixture.bundle.adoptionEvidence.id, "after-stop")
        val later = harness.router.route(
            fixture.bundle,
            EvolutionCanaryRoutingContext(laterKey, "after-stop", setOf(TASK_TAG)),
        )
        val baseline = assertIs<EvolutionCanaryRoute.Baseline>(later)
        assertEquals("canary-kill-switch:HARD_FAILURE", baseline.reason)

        val readiness = harness.readiness.evaluate(fixture.bundle)
        assertEquals(EvolutionCanaryReadinessDecision.STOPPED, readiness.decision)
        assertEquals(stop.id, readiness.killSwitchEvidenceId)
    }

    @Test
    fun `outcome without exact reservation is rejected`() = runBlocking {
        val fixture = fixture(maxInvocations = 10)
        val harness = harness(fixture)

        assertIllegalArgument {
            harness.coordinator.record(
                fixture.bundle,
                EvolutionCanaryOutcomeInput(
                    reservationId = "missing-reservation",
                    invocationId = "never-routed",
                    success = true,
                    producedExpectedOutput = true,
                    outputFingerprint = "output",
                    latencyMs = 10,
                ),
            )
        }
        assertTrue(harness.outcomes.outcomes(fixture.bundle.adoptionEvidence.id).isEmpty())
    }

    @Test
    fun `forged reservation id is rejected before outcome is stored`() = runBlocking {
        val fixture = fixture(maxInvocations = 10)
        val harness = harness(fixture)
        reserve(harness, fixture, "reserved")

        assertIllegalArgument {
            harness.coordinator.record(
                fixture.bundle,
                EvolutionCanaryOutcomeInput(
                    reservationId = "forged-reservation-id",
                    invocationId = "reserved",
                    success = true,
                    producedExpectedOutput = true,
                    outputFingerprint = "output",
                    latencyMs = 10,
                ),
            )
        }
        assertTrue(harness.outcomes.outcomes(fixture.bundle.adoptionEvidence.id).isEmpty())
    }

    @Test
    fun `identical retry is idempotent while conflicting retry fails closed`() = runBlocking {
        val fixture = fixture(maxInvocations = 10)
        val harness = harness(fixture)
        val route = reserve(harness, fixture, "retry")
        val input = successfulInput(route, "retry", latencyMs = 10)

        val first = harness.coordinator.record(fixture.bundle, input)
        val duplicate = harness.coordinator.record(fixture.bundle, input)
        assertFalse(first.duplicate)
        assertTrue(duplicate.duplicate)
        assertEquals(first.outcome.id, duplicate.outcome.id)
        assertEquals(1, harness.outcomes.outcomes(fixture.bundle.adoptionEvidence.id).size)

        assertIllegalArgument {
            harness.coordinator.record(
                fixture.bundle,
                input.copy(latencyMs = 11),
            )
        }
    }

    @Test
    fun `pending reserved invocation prevents readiness`() = runBlocking {
        val fixture = fixture(maxInvocations = 10)
        val harness = harness(fixture)

        repeat(6) { index -> reserve(harness, fixture, "pending-$index") }
        repeat(5) { index ->
            val reservation = assertNotNull(
                harness.runtime.reservation(fixture.bundle.adoptionEvidence.id, "pending-$index")
            )
            harness.coordinator.record(
                fixture.bundle,
                successfulInput(
                    EvolutionCanaryRoute.Candidate(
                        toolId = TOOL_ID,
                        adoptionEvidenceId = fixture.bundle.adoptionEvidence.id,
                        baselineFallback = fixture.bundle.currentBaseline,
                        reservation = reservation,
                    ),
                    "pending-$index",
                    latencyMs = 10,
                ),
            )
        }

        val readiness = harness.readiness.evaluate(fixture.bundle)
        assertEquals(EvolutionCanaryReadinessDecision.INSUFFICIENT_EVIDENCE, readiness.decision)
        assertEquals(listOf("pending-outcomes:1"), readiness.reasons)
    }

    @Test
    fun `ordinary failed outcome blocks readiness and J03 promotion without tripping kill switch`() = runBlocking {
        val fixture = fixture(maxInvocations = 10)
        val harness = harness(fixture)

        repeat(5) { index ->
            val invocationId = "ordinary-$index"
            val route = reserve(harness, fixture, invocationId)
            val input = if (index == 2) {
                EvolutionCanaryOutcomeInput(
                    reservationId = route.reservation.id,
                    invocationId = invocationId,
                    success = false,
                    producedExpectedOutput = false,
                    latencyMs = 10,
                )
            } else successfulInput(route, invocationId, latencyMs = 10)
            harness.coordinator.record(fixture.bundle, input)
        }

        assertEquals(null, harness.runtime.killSwitch(fixture.bundle.adoptionEvidence.id))
        assertEquals(GeneratedToolState.TRIAL, harness.registry.get(TOOL_ID)?.state)
        val readiness = harness.readiness.evaluate(fixture.bundle)
        assertEquals(EvolutionCanaryReadinessDecision.NOT_READY, readiness.decision)
        assertTrue(readiness.reasons.single().startsWith("success-rate:"))
        assertIs<GeneratedToolPromotionEvaluation.NotReady>(harness.lifecycle.evaluatePromotion(TOOL_ID))
    }

    @Test
    fun `latency regression blocks promotion readiness`() = runBlocking {
        val fixture = fixture(maxInvocations = 10)
        val harness = harness(fixture)

        repeat(5) { index ->
            val invocationId = "slow-$index"
            val route = reserve(harness, fixture, invocationId)
            harness.coordinator.record(
                fixture.bundle,
                successfulInput(route, invocationId, latencyMs = 20),
            )
        }

        val readiness = harness.readiness.evaluate(fixture.bundle)
        assertEquals(EvolutionCanaryReadinessDecision.NOT_READY, readiness.decision)
        assertEquals(listOf("latency-regression"), readiness.reasons)
    }

    @Test
    fun `kill switch is sticky and rejects a second already reserved outcome`() = runBlocking {
        val fixture = fixture(maxInvocations = 10)
        val harness = harness(fixture)
        val first = reserve(harness, fixture, "stop-first")
        val second = reserve(harness, fixture, "stop-second")

        harness.coordinator.record(
            fixture.bundle,
            EvolutionCanaryOutcomeInput(
                reservationId = first.reservation.id,
                invocationId = "stop-first",
                success = false,
                producedExpectedOutput = false,
                latencyMs = 10,
                hardFailures = setOf(EvolutionHardFailure.CONTRACT_VIOLATION),
            ),
        )
        val originalStop = assertNotNull(harness.runtime.killSwitch(fixture.bundle.adoptionEvidence.id))

        assertIllegalArgument {
            harness.coordinator.record(
                fixture.bundle,
                successfulInput(second, "stop-second", latencyMs = 10),
            )
        }
        assertEquals(originalStop.id, harness.runtime.killSwitch(fixture.bundle.adoptionEvidence.id)?.id)
    }

    private data class Harness(
        val runtime: InMemoryEvolutionCanaryBudgetStore,
        val outcomes: InMemoryEvolutionCanaryOutcomeStore,
        val registry: GeneratedToolRegistry,
        val lifecycle: GeneratedToolLifecycleCoordinator,
        val router: EvolutionCanaryRouter,
        val coordinator: EvolutionCanaryOutcomeCoordinator,
        val readiness: EvolutionCanaryReadinessGate,
    )

    private suspend fun harness(fixture: Fixture): Harness {
        val runtime = InMemoryEvolutionCanaryBudgetStore()
        val outcomes = InMemoryEvolutionCanaryOutcomeStore()
        val registry = GeneratedToolRegistry()
        registry.register(fixture.bundle.currentCandidate)
        val lifecycle = GeneratedToolLifecycleCoordinator(registry)
        return Harness(
            runtime = runtime,
            outcomes = outcomes,
            registry = registry,
            lifecycle = lifecycle,
            router = EvolutionCanaryRouter(runtime),
            coordinator = EvolutionCanaryOutcomeCoordinator(runtime, outcomes, lifecycle),
            readiness = EvolutionCanaryReadinessGate(runtime, outcomes),
        )
    }

    private suspend fun reserve(
        harness: Harness,
        fixture: Fixture,
        invocationId: String,
    ): EvolutionCanaryRoute.Candidate {
        val key = candidateAssignmentKey(harness.router, fixture.bundle.adoptionEvidence.id, invocationId)
        return assertIs(
            harness.router.route(
                fixture.bundle,
                EvolutionCanaryRoutingContext(key, invocationId, setOf(TASK_TAG)),
            )
        )
    }

    private fun candidateAssignmentKey(
        router: EvolutionCanaryRouter,
        evidenceId: String,
        suffix: String,
    ): String {
        var index = 0
        while (true) {
            val key = "candidate-$suffix-${index++}"
            if (router.assignmentBucket(evidenceId, key) < 100) return key
        }
    }

    private fun successfulInput(
        route: EvolutionCanaryRoute.Candidate,
        invocationId: String,
        latencyMs: Long,
    ) = EvolutionCanaryOutcomeInput(
        reservationId = route.reservation.id,
        invocationId = invocationId,
        success = true,
        producedExpectedOutput = true,
        outputFingerprint = "output:$invocationId",
        latencyMs = latencyMs,
    )

    private suspend fun assertIllegalArgument(block: suspend () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed, "Expected IllegalArgumentException")
    }

    private data class Fixture(val bundle: EvolutionCanaryEvidenceBundle)

    private fun fixture(maxInvocations: Int): Fixture {
        val candidate = candidate()
        val baseline = baseline()
        val subject = EvolutionSubject.create(candidateArtifact(), candidate, baseline)
        val dataset = EvolutionDatasetRef("j07-holdout", "j07-holdout-fingerprint", CURATOR_ID)
        val cases = List(5) { index ->
            EvolutionTestCase("case-$index", "input-$index", "expected-$index")
        }
        val observations = cases.flatMapIndexed { index, case ->
            listOf(
                EvolutionShadowObservation(
                    caseId = case.caseId,
                    side = EvolutionObservationSide.BASELINE,
                    providerId = BASELINE_ID,
                    success = true,
                    outputFingerprint = "baseline-$index",
                    qualityScore = 0.80,
                    latencyMs = 10,
                    peakMemoryBytes = 1_000,
                    recordedAt = evidenceTime.plusSeconds(index.toLong()),
                ),
                EvolutionShadowObservation(
                    caseId = case.caseId,
                    side = EvolutionObservationSide.CANDIDATE,
                    providerId = TOOL_ID,
                    success = true,
                    outputFingerprint = case.expectedOutputFingerprint,
                    qualityScore = 0.95,
                    latencyMs = 10,
                    peakMemoryBytes = 1_000,
                    recordedAt = evidenceTime.plusSeconds(100L + index),
                ),
            )
        }
        val report = ShadowEvaluationEngine().evaluate(subject, dataset, EVALUATOR_ID, cases, observations)
        val request = EvolutionAdoptionRequest(
            actorId = ADOPTION_ACTOR_ID,
            evidenceRef = "j07-adoption-review",
            rationale = "bounded canary outcome collection",
            scope = EvolutionCanaryScope(
                assignmentPermille = 100,
                maxInvocations = maxInvocations,
                maxDurationSeconds = 3_600,
                allowedTaskTags = setOf(TASK_TAG),
            ),
            occurredAt = Instant.now().minusSeconds(60),
        )
        val adoption = EvolutionAdoptionGate().evaluate(
            subject,
            dataset,
            report,
            cases,
            observations,
            candidate,
            baseline,
            request,
        )
        return Fixture(
            EvolutionCanaryEvidenceBundle(
                subject = subject,
                dataset = dataset,
                evaluationReport = report,
                cases = cases,
                observations = observations,
                adoptionEvidence = adoption,
                adoptionRequest = request,
                currentCandidate = candidate,
                currentBaseline = baseline,
            )
        )
    }

    private fun baseline() = CapabilityDescriptor(
        capabilityId = CAPABILITY_ID,
        providerId = BASELINE_ID,
        providerType = ProviderType.MODULE,
        contract = CapabilityContract(setOf("text"), setOf("normalized-text")),
        state = ProviderState.ACTIVE,
        trustLevel = TrustLevel.SYSTEM,
        reliability = 0.99,
    )

    private fun candidate() = GeneratedToolRecord(
        manifest = GeneratedToolManifest(
            toolId = TOOL_ID,
            sourceCapability = CAPABILITY_ID,
            sourceHash = "j07-source-hash",
            buildHash = APK_SHA,
            permissions = emptySet(),
            generatedAt = evidenceTime,
            requiredInputs = setOf("text"),
            requiredOutputs = setOf("normalized-text"),
        ),
        state = GeneratedToolState.TRIAL,
        verificationConfidence = 0.96,
    )

    private fun candidateArtifact(): CandidateArtifact {
        val requirement = CapabilityRequirement(
            capabilityId = CAPABILITY_ID,
            requiredInputs = setOf("text"),
            requiredOutputs = setOf("normalized-text"),
        )
        val spec = BuildSpec(
            sourceCommit = SOURCE_COMMIT,
            gap = CapabilityGap(requirement, CapabilityGapType.CAPABILITY_MISSING),
            allowedPathPrefixes = setOf(SOURCE_PREFIX, TEST_PREFIX),
            requiredTestPaths = setOf(TEST_PATH),
        )
        val design = BuildDesignSpec(
            buildSpecId = spec.id,
            capability = requirement,
            summary = "J07 canary candidate",
            implementationNotes = listOf("outcome-bound", "kill-switch"),
            plannedSourcePaths = setOf(SOURCE_PATH),
            plannedTestPaths = setOf(TEST_PATH),
        )
        val patch = SourcePatchPlan(
            designSpecId = design.id,
            operations = listOf(
                SourcePatchOperation(SourcePatchOperationType.CREATE, SOURCE_PATH, "class J07Candidate"),
                SourcePatchOperation(SourcePatchOperationType.CREATE, TEST_PATH, "class J07CandidateTest"),
            ),
        )
        val commands = BuildGateCommand.entries.map { command ->
            BuildCommandResult(command, true, 0, "gate:${command.name}")
        }
        val verification = BuildVerificationPolicy().verify(
            BuildVerificationEvidence(
                branchName = BRANCH_NAME,
                branchHeadCommit = APPLIED_HEAD,
                patchPlanId = patch.id,
                commandResults = commands,
                artifact = BuildArtifactEvidence("artifact://j07-debug.apk", APK_SHA),
            )
        )
        val buildCandidate = BuildStudioCandidate(
            buildSpecId = spec.id,
            designSpecId = design.id,
            patchPlanId = patch.id,
            branchName = BRANCH_NAME,
            branchHeadCommit = APPLIED_HEAD,
            verificationId = verification.id,
        )
        val provenance = BuildProvenance.fromVerifiedCandidate(
            spec = spec,
            design = design,
            patch = patch,
            candidate = buildCandidate,
            verification = verification,
            capabilityChanges = listOf(
                BuildCapabilityChange(
                    capabilityId = CAPABILITY_ID,
                    type = BuildCapabilityChangeType.ADDED,
                    requiredInputs = requirement.requiredInputs,
                    outputs = requirement.requiredOutputs,
                )
            ),
        )
        return CandidateArtifact(buildCandidate, verification, provenance)
    }

    companion object {
        private val CAPABILITY_ID = CapabilityId("text.normalize")
        private const val TOOL_ID = "tool-j07"
        private const val BASELINE_ID = "module-text-normalize-v1"
        private const val EVALUATOR_ID = "evaluator:independent-j07"
        private const val CURATOR_ID = "curator:independent-j07"
        private const val ADOPTION_ACTOR_ID = "adoption:local-user-j07"
        private const val SOURCE_COMMIT = "dd431217a9c43004ebade22cc779ba35aa0d0b3c"
        private const val APPLIED_HEAD = "ffffffffffffffffffffffffffffffffffffffff"
        private const val APK_SHA = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        private const val BRANCH_NAME = "buildstudio/candidate-j07"
        private const val TASK_TAG = "text-normalize"
        private const val SOURCE_PREFIX = "core/runtime/src/main/kotlin/app/lifeos/core/runtime/generated"
        private const val TEST_PREFIX = "core/runtime/src/test/kotlin/app/lifeos/core/runtime/generated"
        private const val SOURCE_PATH = "$SOURCE_PREFIX/J07Candidate.kt"
        private const val TEST_PATH = "$TEST_PREFIX/J07CandidateTest.kt"
    }
}
