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
import app.lifeos.core.runtime.capability.GeneratedToolRecord
import app.lifeos.core.runtime.capability.GeneratedToolRegistry
import app.lifeos.core.runtime.capability.GeneratedToolState
import app.lifeos.core.runtime.capability.ProviderState
import app.lifeos.core.runtime.capability.ProviderType
import app.lifeos.core.runtime.capability.TrustLevel
import app.lifeos.core.runtime.trace.DecisionTrace
import app.lifeos.core.runtime.trace.DecisionTraceId
import app.lifeos.core.runtime.trace.DecisionTraceLedger
import app.lifeos.core.runtime.trace.DecisionTraceRepository
import app.lifeos.core.runtime.trace.DecisionTraceRepositoryLoadReport
import app.lifeos.core.runtime.trace.LifecycleDecisionTraceRecorder
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EvolutionDecisionTraceWiringTest {
    private val evidenceTime = Instant.parse("2026-09-14T10:30:00Z")

    @Test
    fun `durable canary outcome records trace and exact retry is idempotent`() = runTest {
        val fixture = fixture()
        val runtime = InMemoryEvolutionCanaryBudgetStore()
        val outcomes = InMemoryEvolutionCanaryOutcomeStore()
        val registry = GeneratedToolRegistry()
        registry.register(fixture.currentCandidate)
        val lifecycle = GeneratedToolLifecycleCoordinator(registry)
        val router = EvolutionCanaryRouter(runtime)
        val traces = DecisionTraceLedger(MemoryTraceRepository())
        val coordinator = EvolutionCanaryOutcomeCoordinator(
            runtimeStore = runtime,
            outcomeStore = outcomes,
            lifecycle = lifecycle,
            lifecycleTraceRecorder = LifecycleDecisionTraceRecorder(traces),
        )
        val route = reserve(router, fixture, "trace-success")
        val input = EvolutionCanaryOutcomeInput(
            reservationId = route.reservation.id,
            invocationId = "trace-success",
            success = true,
            producedExpectedOutput = true,
            outputFingerprint = "output:trace-success",
            latencyMs = 10,
        )

        val first = coordinator.record(fixture, input)
        val traceId = DecisionTraceId.create("evolution-adoption", fixture.adoptionEvidence.id)
        val firstTrace = assertNotNull(traces.snapshot(traceId))
        assertTrue(firstTrace.nodes.any {
            it.sourceType == "evolution-canary-outcome" && it.sourceId == first.outcome.id
        })

        val retry = coordinator.record(fixture, input)
        val replayTrace = assertNotNull(traces.snapshot(traceId))
        assertTrue(retry.duplicate)
        assertEquals(firstTrace.revision, replayTrace.revision)
    }

    @Test
    fun `trial ledger sync failure is traced with durable kill switch before failure escapes`() = runTest {
        val fixture = fixture()
        val runtime = InMemoryEvolutionCanaryBudgetStore()
        val outcomes = InMemoryEvolutionCanaryOutcomeStore()
        val router = EvolutionCanaryRouter(runtime)
        val traces = DecisionTraceLedger(MemoryTraceRepository())
        val coordinator = EvolutionCanaryOutcomeCoordinator(
            runtimeStore = runtime,
            outcomeStore = outcomes,
            lifecycle = GeneratedToolLifecycleCoordinator(GeneratedToolRegistry()),
            lifecycleTraceRecorder = LifecycleDecisionTraceRecorder(traces),
        )
        val route = reserve(router, fixture, "trace-sync-failure")
        val input = EvolutionCanaryOutcomeInput(
            reservationId = route.reservation.id,
            invocationId = "trace-sync-failure",
            success = true,
            producedExpectedOutput = true,
            outputFingerprint = "output:trace-sync-failure",
            latencyMs = 10,
        )

        var failed = false
        try {
            coordinator.record(fixture, input)
        } catch (_: Exception) {
            failed = true
        }
        assertTrue(failed, "Expected missing lifecycle tool registration to fail trial-ledger sync")

        val outcome = assertNotNull(outcomes.outcome(fixture.adoptionEvidence.id, input.invocationId))
        val killSwitch = assertNotNull(runtime.killSwitch(fixture.adoptionEvidence.id))
        assertEquals(EvolutionCanaryStopReason.TRIAL_LEDGER_SYNC_FAILED, killSwitch.reason)
        val trace = assertNotNull(
            traces.snapshot(DecisionTraceId.create("evolution-adoption", fixture.adoptionEvidence.id))
        )
        assertTrue(trace.nodes.any {
            it.sourceType == "evolution-canary-outcome" && it.sourceId == outcome.id
        })
        assertTrue(trace.nodes.any {
            it.sourceType == "evolution-kill-switch" && it.sourceId == killSwitch.id
        })
    }

    private suspend fun reserve(
        router: EvolutionCanaryRouter,
        fixture: EvolutionCanaryEvidenceBundle,
        invocationId: String,
    ): EvolutionCanaryRoute.Candidate {
        var index = 0
        while (true) {
            val key = "candidate-$invocationId-${index++}"
            if (router.assignmentBucket(fixture.adoptionEvidence.id, key) < 100) {
                return router.route(
                    fixture,
                    EvolutionCanaryRoutingContext(key, invocationId, setOf(TASK_TAG)),
                ) as EvolutionCanaryRoute.Candidate
            }
        }
    }

    private fun fixture(): EvolutionCanaryEvidenceBundle {
        val candidate = candidate()
        val baseline = baseline()
        val subject = EvolutionSubject.create(candidateArtifact(), candidate, baseline)
        val dataset = EvolutionDatasetRef("v15-holdout", "v15-holdout-fingerprint", CURATOR_ID)
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
        val adoptionRequest = EvolutionAdoptionRequest(
            actorId = ADOPTION_ACTOR_ID,
            evidenceRef = "v15-trace-wiring-review",
            rationale = "decision trace wiring",
            scope = EvolutionCanaryScope(
                assignmentPermille = 100,
                maxInvocations = 10,
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
            adoptionRequest,
        )
        return EvolutionCanaryEvidenceBundle(
            subject = subject,
            dataset = dataset,
            evaluationReport = report,
            cases = cases,
            observations = observations,
            adoptionEvidence = adoption,
            adoptionRequest = adoptionRequest,
            currentCandidate = candidate,
            currentBaseline = baseline,
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
            sourceHash = "v15-source-hash",
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
            summary = "V15 trace candidate",
            implementationNotes = listOf("outcome-bound", "trace-bound"),
            plannedSourcePaths = setOf(SOURCE_PATH),
            plannedTestPaths = setOf(TEST_PATH),
        )
        val patch = SourcePatchPlan(
            designSpecId = design.id,
            operations = listOf(
                SourcePatchOperation(SourcePatchOperationType.CREATE, SOURCE_PATH, "class V15Candidate"),
                SourcePatchOperation(SourcePatchOperationType.CREATE, TEST_PATH, "class V15CandidateTest"),
            ),
        )
        val commandResults = BuildGateCommand.entries.map { command ->
            BuildCommandResult(command, true, 0, "gate:${command.name}")
        }
        val verification = BuildVerificationPolicy().verify(
            BuildVerificationEvidence(
                branchName = BRANCH_NAME,
                branchHeadCommit = APPLIED_HEAD,
                patchPlanId = patch.id,
                commandResults = commandResults,
                artifact = BuildArtifactEvidence("artifact://v15-debug.apk", APK_SHA),
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

    private class MemoryTraceRepository : DecisionTraceRepository {
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
        val CAPABILITY_ID = CapabilityId("text.normalize.v15")
        const val TOOL_ID = "tool-v15-trace"
        const val BASELINE_ID = "module-text-normalize-v15"
        const val EVALUATOR_ID = "evaluator:v15"
        const val CURATOR_ID = "curator:v15"
        const val ADOPTION_ACTOR_ID = "adoption:v15"
        const val SOURCE_COMMIT = "dd431217a9c43004ebade22cc779ba35aa0d0b3c"
        const val APPLIED_HEAD = "ffffffffffffffffffffffffffffffffffffffff"
        const val APK_SHA = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val BRANCH_NAME = "buildstudio/candidate-v15-trace"
        const val TASK_TAG = "text-normalize"
        const val SOURCE_PREFIX = "core/runtime/src/main/kotlin/app/lifeos/core/runtime/generated"
        const val TEST_PREFIX = "core/runtime/src/test/kotlin/app/lifeos/core/runtime/generated"
        const val SOURCE_PATH = "$SOURCE_PREFIX/V15Candidate.kt"
        const val TEST_PATH = "$TEST_PREFIX/V15CandidateTest.kt"
    }
}
