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
import app.lifeos.core.runtime.capability.GeneratedToolManifest
import app.lifeos.core.runtime.capability.GeneratedToolRecord
import app.lifeos.core.runtime.capability.GeneratedToolState
import app.lifeos.core.runtime.capability.ProviderState
import app.lifeos.core.runtime.capability.ProviderType
import app.lifeos.core.runtime.capability.TrustLevel
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EvolutionCanaryRouterTest {
    private val evidenceTime = Instant.parse("2026-09-10T12:00:00Z")

    @Test
    fun `approved replay can route bounded non critical task to trial candidate`() = runBlocking {
        val fixture = fixture(maxInvocations = 5)
        val store = InMemoryEvolutionCanaryBudgetStore()
        val router = EvolutionCanaryRouter(store)
        val assignmentKey = candidateAssignmentKey(router, fixture.bundle.adoptionEvidence.id)
        val beforeRoute = Instant.now()

        val route = router.route(
            fixture.bundle,
            context(assignmentKey = assignmentKey, invocationId = "inv-1"),
        )
        val afterRoute = Instant.now()

        val candidate = assertIs<EvolutionCanaryRoute.Candidate>(route)
        assertEquals(TOOL_ID, candidate.toolId)
        assertEquals(BASELINE_ID, candidate.baselineFallback.providerId)
        assertEquals(1, candidate.reservation.sequence)
        assertTrue(!candidate.reservation.reservedAt.isBefore(beforeRoute))
        assertTrue(!candidate.reservation.reservedAt.isAfter(afterRoute))
        assertEquals(1, store.usedInvocations(fixture.bundle.adoptionEvidence.id))
        assertEquals(GeneratedToolState.TRIAL, fixture.bundle.currentCandidate.state)
    }

    @Test
    fun `critical tags and assignment failures fall back without spending budget`() = runBlocking {
        val fixture = fixture(maxInvocations = 5)
        val store = InMemoryEvolutionCanaryBudgetStore()
        val router = EvolutionCanaryRouter(store)
        val candidateKey = candidateAssignmentKey(router, fixture.bundle.adoptionEvidence.id)
        val baselineKey = baselineAssignmentKey(router, fixture.bundle.adoptionEvidence.id)

        assertBaselineReason(
            router.route(fixture.bundle, context(candidateKey, "critical", critical = true)),
            "critical-context",
        )
        assertBaselineReason(
            router.route(fixture.bundle, context(candidateKey, "wrong-tag", taskTags = setOf("other"))),
            "task-outside-canary-scope",
        )
        assertBaselineReason(
            router.route(
                fixture.bundle,
                context(candidateKey, "mixed-tag", taskTags = setOf(TASK_TAG, "sensitive-other")),
            ),
            "task-outside-canary-scope",
        )
        assertBaselineReason(
            router.route(fixture.bundle, context(baselineKey, "assignment")),
            "assignment-baseline",
        )
        assertEquals(0, store.usedInvocations(fixture.bundle.adoptionEvidence.id))
    }

    @Test
    fun `system time enforces future start and expiry without caller time input`() = runBlocking {
        val futureFixture = fixture(
            maxInvocations = 5,
            requestAt = Instant.now().plusSeconds(600),
        )
        val expiredFixture = fixture(
            maxInvocations = 5,
            requestAt = Instant.now().minusSeconds(7_200),
            maxDurationSeconds = 3_600,
        )
        val futureStore = InMemoryEvolutionCanaryBudgetStore()
        val expiredStore = InMemoryEvolutionCanaryBudgetStore()
        val futureRouter = EvolutionCanaryRouter(futureStore)
        val expiredRouter = EvolutionCanaryRouter(expiredStore)

        val futureKey = candidateAssignmentKey(futureRouter, futureFixture.bundle.adoptionEvidence.id)
        val expiredKey = candidateAssignmentKey(expiredRouter, expiredFixture.bundle.adoptionEvidence.id)

        assertBaselineReason(
            futureRouter.route(futureFixture.bundle, context(futureKey, "future")),
            "canary-not-started",
        )
        assertBaselineReason(
            expiredRouter.route(expiredFixture.bundle, context(expiredKey, "expired")),
            "canary-expired",
        )
        assertEquals(0, futureStore.usedInvocations(futureFixture.bundle.adoptionEvidence.id))
        assertEquals(0, expiredStore.usedInvocations(expiredFixture.bundle.adoptionEvidence.id))
    }

    @Test
    fun `invocation budget is fail closed after exact maximum`() = runBlocking {
        val fixture = fixture(maxInvocations = 2)
        val store = InMemoryEvolutionCanaryBudgetStore()
        val router = EvolutionCanaryRouter(store)
        val keys = candidateAssignmentKeys(router, fixture.bundle.adoptionEvidence.id, 3)

        assertIs<EvolutionCanaryRoute.Candidate>(
            router.route(fixture.bundle, context(keys[0], "inv-1"))
        )
        assertIs<EvolutionCanaryRoute.Candidate>(
            router.route(fixture.bundle, context(keys[1], "inv-2"))
        )
        assertBaselineReason(
            router.route(fixture.bundle, context(keys[2], "inv-3")),
            "invocation-budget-exhausted",
        )
        assertEquals(2, store.usedInvocations(fixture.bundle.adoptionEvidence.id))
    }

    @Test
    fun `duplicate invocation is idempotent and consumes one budget slot`() = runBlocking {
        val fixture = fixture(maxInvocations = 2)
        val store = InMemoryEvolutionCanaryBudgetStore()
        val router = EvolutionCanaryRouter(store)
        val key = candidateAssignmentKey(router, fixture.bundle.adoptionEvidence.id)

        val first = assertIs<EvolutionCanaryRoute.Candidate>(
            router.route(fixture.bundle, context(key, "same-invocation"))
        )
        val retry = assertIs<EvolutionCanaryRoute.Candidate>(
            router.route(fixture.bundle, context(key, "same-invocation"))
        )

        assertEquals(first.reservation.id, retry.reservation.id)
        assertEquals(1, store.usedInvocations(fixture.bundle.adoptionEvidence.id))
    }

    @Test
    fun `concurrent claims cannot overspend atomic budget`() = runBlocking {
        val fixture = fixture(maxInvocations = 10)
        val store = InMemoryEvolutionCanaryBudgetStore()
        val router = EvolutionCanaryRouter(store)
        val keys = candidateAssignmentKeys(router, fixture.bundle.adoptionEvidence.id, 40)

        val routes = coroutineScope {
            keys.mapIndexed { index, key ->
                async { router.route(fixture.bundle, context(key, "parallel-$index")) }
            }.awaitAll()
        }

        assertEquals(10, routes.count { it is EvolutionCanaryRoute.Candidate })
        assertEquals(10, store.usedInvocations(fixture.bundle.adoptionEvidence.id))
        assertEquals(30, routes.count {
            it is EvolutionCanaryRoute.Baseline && it.reason == "invocation-budget-exhausted"
        })
    }

    @Test
    fun `caller supplied adoption evidence cannot bypass trusted replay`() = runBlocking {
        val fixture = fixture(maxInvocations = 5)
        val original = fixture.bundle.adoptionEvidence
        val forged = EvolutionAdoptionEvidence.create(
            subjectId = original.subjectId,
            evaluationReportId = original.evaluationReportId,
            datasetId = original.datasetId,
            policyId = original.policyId,
            candidateRecordFingerprint = original.candidateRecordFingerprint,
            baselineDescriptorFingerprint = original.baselineDescriptorFingerprint,
            adoptionRequestId = original.adoptionRequestId,
            actorId = original.actorId,
            canaryScopeId = original.canaryScopeId,
            decision = EvolutionAdoptionDecision.APPROVED_FOR_CANARY,
            reasons = listOf("forged-approval"),
        )
        val router = EvolutionCanaryRouter(InMemoryEvolutionCanaryBudgetStore())
        val key = candidateAssignmentKey(router, forged.id)

        assertIllegalArgument {
            router.route(fixture.bundle.copy(adoptionEvidence = forged), context(key, "forged"))
        }
    }

    @Test
    fun `stale candidate or baseline fails before budget reservation`() = runBlocking {
        val fixture = fixture(maxInvocations = 5)
        val store = InMemoryEvolutionCanaryBudgetStore()
        val router = EvolutionCanaryRouter(store)
        val key = candidateAssignmentKey(router, fixture.bundle.adoptionEvidence.id)

        assertIllegalArgument {
            router.route(
                fixture.bundle.copy(
                    currentCandidate = fixture.bundle.currentCandidate.copy(lastMessage = "mutated")
                ),
                context(key, "candidate-stale"),
            )
        }
        assertIllegalArgument {
            router.route(
                fixture.bundle.copy(
                    currentBaseline = fixture.bundle.currentBaseline.copy(reliability = 0.25)
                ),
                context(key, "baseline-stale"),
            )
        }
        assertEquals(0, store.usedInvocations(fixture.bundle.adoptionEvidence.id))
    }

    @Test
    fun `assignment bucket is deterministic and bounded`() {
        val router = EvolutionCanaryRouter(InMemoryEvolutionCanaryBudgetStore())
        val evidenceId = "evidence-j06"
        val first = router.assignmentBucket(evidenceId, "stable-user")
        val second = router.assignmentBucket(evidenceId, "stable-user")

        assertEquals(first, second)
        assertTrue(first in 0..999)
    }

    private suspend fun assertIllegalArgument(block: suspend () -> Unit) {
        var failedClosed = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            failedClosed = true
        }
        assertTrue(failedClosed, "Expected IllegalArgumentException")
    }

    private fun assertBaselineReason(route: EvolutionCanaryRoute, expected: String) {
        val baseline = assertIs<EvolutionCanaryRoute.Baseline>(route)
        assertEquals(expected, baseline.reason)
        assertEquals(BASELINE_ID, baseline.provider.providerId)
    }

    private fun context(
        assignmentKey: String,
        invocationId: String,
        taskTags: Set<String> = setOf(TASK_TAG),
        critical: Boolean = false,
    ) = EvolutionCanaryRoutingContext(
        assignmentKey = assignmentKey,
        invocationId = invocationId,
        taskTags = taskTags,
        critical = critical,
    )

    private fun candidateAssignmentKey(
        router: EvolutionCanaryRouter,
        evidenceId: String,
    ): String = candidateAssignmentKeys(router, evidenceId, 1).single()

    private fun candidateAssignmentKeys(
        router: EvolutionCanaryRouter,
        evidenceId: String,
        count: Int,
    ): List<String> {
        val result = mutableListOf<String>()
        var index = 0
        while (result.size < count) {
            val key = "candidate-key-${index++}"
            if (router.assignmentBucket(evidenceId, key) < 100) result += key
        }
        return result
    }

    private fun baselineAssignmentKey(
        router: EvolutionCanaryRouter,
        evidenceId: String,
    ): String {
        var index = 0
        while (true) {
            val key = "baseline-key-${index++}"
            if (router.assignmentBucket(evidenceId, key) >= 100) return key
        }
    }

    private data class Fixture(
        val bundle: EvolutionCanaryEvidenceBundle,
        val request: EvolutionAdoptionRequest,
    )

    private fun fixture(
        maxInvocations: Int,
        requestAt: Instant = Instant.now().minusSeconds(300),
        maxDurationSeconds: Long = 3_600,
    ): Fixture {
        val candidate = candidate()
        val baseline = baseline()
        val subject = EvolutionSubject.create(candidateArtifact(), candidate, baseline)
        val dataset = EvolutionDatasetRef("j06-holdout", "j06-holdout-fingerprint", CURATOR_ID)
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
        val report = ShadowEvaluationEngine().evaluate(
            subject, dataset, EVALUATOR_ID, cases, observations,
        )
        val request = EvolutionAdoptionRequest(
            actorId = ADOPTION_ACTOR_ID,
            evidenceRef = "j06-adoption-review",
            rationale = "bounded canary",
            scope = EvolutionCanaryScope(
                assignmentPermille = 100,
                maxInvocations = maxInvocations,
                maxDurationSeconds = maxDurationSeconds,
                allowedTaskTags = setOf(TASK_TAG),
            ),
            occurredAt = requestAt,
        )
        val adoptionEvidence = EvolutionAdoptionGate().evaluate(
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
            bundle = EvolutionCanaryEvidenceBundle(
                subject = subject,
                dataset = dataset,
                evaluationReport = report,
                cases = cases,
                observations = observations,
                adoptionEvidence = adoptionEvidence,
                adoptionRequest = request,
                currentCandidate = candidate,
                currentBaseline = baseline,
            ),
            request = request,
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
            sourceHash = "j06-source-hash",
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
            summary = "J06 canary candidate",
            implementationNotes = listOf("bounded", "replay-gated"),
            plannedSourcePaths = setOf(SOURCE_PATH),
            plannedTestPaths = setOf(TEST_PATH),
        )
        val patch = SourcePatchPlan(
            designSpecId = design.id,
            operations = listOf(
                SourcePatchOperation(SourcePatchOperationType.CREATE, SOURCE_PATH, "class J06Candidate"),
                SourcePatchOperation(SourcePatchOperationType.CREATE, TEST_PATH, "class J06CandidateTest"),
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
                artifact = BuildArtifactEvidence("artifact://j06-debug.apk", APK_SHA),
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
        private const val TOOL_ID = "tool-j06"
        private const val BASELINE_ID = "module-text-normalize-v1"
        private const val EVALUATOR_ID = "evaluator:independent-j06"
        private const val CURATOR_ID = "curator:independent-j06"
        private const val ADOPTION_ACTOR_ID = "adoption:local-user-j06"
        private const val TASK_TAG = "text-normalize"
        private const val SOURCE_COMMIT = "ef4adccb7fe6d4bcd413170c0e67b11f4d44ab7e"
        private const val APPLIED_HEAD = "ffffffffffffffffffffffffffffffffffffffff"
        private const val APK_SHA = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        private const val BRANCH_NAME = "buildstudio/candidate-j06"
        private const val SOURCE_PREFIX = "core/runtime/src/main/kotlin/app/lifeos/core/runtime/generated"
        private const val TEST_PREFIX = "core/runtime/src/test/kotlin/app/lifeos/core/runtime/generated"
        private const val SOURCE_PATH = "$SOURCE_PREFIX/J06Candidate.kt"
        private const val TEST_PATH = "$TEST_PREFIX/J06CandidateTest.kt"
    }
}
