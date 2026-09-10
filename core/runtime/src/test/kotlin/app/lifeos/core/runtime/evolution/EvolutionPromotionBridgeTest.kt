package app.lifeos.core.runtime.evolution

import app.lifeos.core.runtime.buildstudio.BuildActorAction
import app.lifeos.core.runtime.buildstudio.BuildActorEvidence
import app.lifeos.core.runtime.buildstudio.BuildActorRole
import app.lifeos.core.runtime.buildstudio.BuildArtifactEvidence
import app.lifeos.core.runtime.buildstudio.BuildCapabilityChange
import app.lifeos.core.runtime.buildstudio.BuildCapabilityChangeType
import app.lifeos.core.runtime.buildstudio.BuildCommandResult
import app.lifeos.core.runtime.buildstudio.BuildDesignSpec
import app.lifeos.core.runtime.buildstudio.BuildGateCommand
import app.lifeos.core.runtime.buildstudio.BuildPermissionDelta
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
import app.lifeos.core.runtime.capability.CapabilityRegistry
import app.lifeos.core.runtime.capability.CapabilityRequirement
import app.lifeos.core.runtime.capability.GeneratedToolAuditAction
import app.lifeos.core.runtime.capability.GeneratedToolLifecycleCoordinator
import app.lifeos.core.runtime.capability.GeneratedToolManifest
import app.lifeos.core.runtime.capability.GeneratedToolRecord
import app.lifeos.core.runtime.capability.GeneratedToolRegistry
import app.lifeos.core.runtime.capability.GeneratedToolState
import app.lifeos.core.runtime.capability.GeneratedToolTrialResult
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EvolutionPromotionBridgeTest {
    private val artifactTime = Instant.parse("2026-09-10T12:00:00Z")

    @Test
    fun `clean full evidence chain seals canary and permits explicit J08 promotion`() = runTest {
        val fixture = fixture()
        fixture.recordCleanOutcomes(5)
        val readiness = fixture.readinessGate.evaluate(fixture.bundle)
        assertEquals(EvolutionCanaryReadinessDecision.READY_FOR_PROMOTION_REVIEW, readiness.decision)

        val request = fixture.reviewRequest()
        val review = fixture.bridge.prepareReview(fixture.bundle, fixture.artifact, request)

        assertFalse(review.activationAllowed)
        assertEquals(readiness.id, review.readinessEvidenceId)
        val seal = assertNotNull(fixture.runtimeStore.promotionSeal(fixture.bundle.adoptionEvidence.id))
        assertEquals(seal.id, review.promotionSealEvidenceId)
        assertFalse(seal.activationAllowed)

        val sealedRoute = fixture.router.route(
            fixture.bundle,
            EvolutionCanaryRoutingContext(
                assignmentKey = "irrelevant-after-seal",
                invocationId = "after-seal",
                taskTags = setOf(TASK_TAG),
            ),
        )
        val baseline = assertIs<EvolutionCanaryRoute.Baseline>(sealedRoute)
        assertEquals("canary-promotion-sealed", baseline.reason)
        assertEquals(BASELINE_ID, baseline.provider.providerId)

        val result = fixture.bridge.promote(
            evidence = fixture.bundle,
            artifact = fixture.artifact,
            request = request,
            review = review,
        )

        assertEquals(GeneratedToolState.ACTIVE, result.record.state)
        assertEquals(result.j03PromotionEvidence.id, result.record.promotionEvidenceId)
        assertEquals(review.id, result.reviewEvidence.id)
        val generatedProvider = fixture.capabilities.providersFor(CAPABILITY_ID).single { it.providerId == TOOL_ID }
        assertEquals(ProviderType.GENERATED_TOOL, generatedProvider.providerType)
        assertEquals(TrustLevel.LOW, generatedProvider.trustLevel)

        val audit = fixture.tools.auditSnapshot(TOOL_ID)
        assertEquals(GeneratedToolAuditAction.PROMOTED, audit.last().action)
        assertEquals(review.id, audit.last().evidenceRef)
        assertEquals(PROMOTION_ACTOR, audit.last().actorId)
        assertTrue(audit.last().reason?.contains(result.j03PromotionEvidence.id) == true)
        assertTrue(fixture.tools.verifyAuditChain(TOOL_ID))
    }

    @Test
    fun `pending canary outcome cannot be sealed or promoted`() = runTest {
        val fixture = fixture()
        fixture.reserve("pending-0")
        repeat(5) { index ->
            val invocationId = "done-$index"
            val reservation = fixture.reserve(invocationId)
            fixture.recordCleanOutcome(invocationId, reservation)
        }

        val readiness = fixture.readinessGate.evaluate(fixture.bundle)
        assertEquals(EvolutionCanaryReadinessDecision.INSUFFICIENT_EVIDENCE, readiness.decision)
        assertTrue(readiness.reasons.any { it.startsWith("pending-outcomes:") })

        assertFailsWith<IllegalArgumentException> {
            fixture.bridge.prepareReview(fixture.bundle, fixture.artifact, fixture.reviewRequest())
        }
        assertNull(fixture.runtimeStore.promotionSeal(fixture.bundle.adoptionEvidence.id))
        assertEquals(GeneratedToolState.TRIAL, fixture.tools.get(TOOL_ID)?.state)
        assertTrue(fixture.capabilities.providersFor(CAPABILITY_ID).none { it.providerId == TOOL_ID })
    }

    @Test
    fun `hard canary failure blocks J08 and leaves exact baseline as fallback`() = runTest {
        val fixture = fixture()
        repeat(4) { index ->
            val id = "clean-$index"
            fixture.recordCleanOutcome(id, fixture.reserve(id))
        }
        val badId = "hard-failure"
        val badReservation = fixture.reserve(badId)
        fixture.outcomeCoordinator.record(
            fixture.bundle,
            EvolutionCanaryOutcomeInput(
                reservationId = badReservation.id,
                invocationId = badId,
                success = false,
                producedExpectedOutput = false,
                latencyMs = 10,
                hardFailures = setOf(EvolutionHardFailure.SAFETY_VIOLATION),
            ),
        )

        assertEquals(GeneratedToolState.QUARANTINED, fixture.tools.get(TOOL_ID)?.state)
        assertNotNull(fixture.runtimeStore.killSwitch(fixture.bundle.adoptionEvidence.id))
        assertFailsWith<IllegalArgumentException> {
            fixture.bridge.prepareReview(fixture.bundle, fixture.artifact, fixture.reviewRequest())
        }
        assertNull(fixture.runtimeStore.promotionSeal(fixture.bundle.adoptionEvidence.id))

        val liveBundle = fixture.bundle.copy(
            currentCandidate = requireNotNull(fixture.tools.get(TOOL_ID)),
        )
        val route = fixture.router.route(
            liveBundle,
            EvolutionCanaryRoutingContext("fallback", "fallback-inv", setOf(TASK_TAG)),
        )
        val baseline = assertIs<EvolutionCanaryRoute.Baseline>(route)
        assertTrue(baseline.reason.startsWith("canary-kill-switch:"))
        assertEquals(BASELINE_ID, baseline.provider.providerId)
    }

    @Test
    fun `final promotion actor must be independent and present in CandidateArtifact evidence`() = runTest {
        val fixture = fixture()
        fixture.recordCleanOutcomes(5)

        assertFailsWith<IllegalArgumentException> {
            fixture.bridge.prepareReview(
                fixture.bundle,
                fixture.artifact,
                fixture.reviewRequest(actorId = ADOPTION_ACTOR),
            )
        }
        assertNull(fixture.runtimeStore.promotionSeal(fixture.bundle.adoptionEvidence.id))

        assertFailsWith<IllegalArgumentException> {
            fixture.bridge.prepareReview(
                fixture.bundle,
                fixture.artifact,
                fixture.reviewRequest(actorId = "promotion:unknown"),
            )
        }
        assertNull(fixture.runtimeStore.promotionSeal(fixture.bundle.adoptionEvidence.id))
    }

    @Test
    fun `J03 trial evidence changing after seal invalidates prepared J08 review`() = runTest {
        val fixture = fixture()
        fixture.recordCleanOutcomes(5)
        val request = fixture.reviewRequest()
        val review = fixture.bridge.prepareReview(fixture.bundle, fixture.artifact, request)
        assertNotNull(fixture.runtimeStore.promotionSeal(fixture.bundle.adoptionEvidence.id))

        fixture.lifecycle.recordTrial(
            TOOL_ID,
            GeneratedToolTrialResult(
                invocationId = "late-direct-trial",
                success = true,
                producedExpectedOutput = true,
                latencyMs = 9,
                recordedAt = Instant.now(),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            fixture.bridge.promote(fixture.bundle, fixture.artifact, request, review)
        }
        assertEquals(GeneratedToolState.TRIAL, fixture.tools.get(TOOL_ID)?.state)
        assertTrue(fixture.capabilities.providersFor(CAPABILITY_ID).none { it.providerId == TOOL_ID })
    }

    @Test
    fun `promotion seal is atomic sticky and blocks direct reservation after review`() = runTest {
        val fixture = fixture()
        fixture.recordCleanOutcomes(5)
        val readiness = fixture.readinessGate.evaluate(fixture.bundle)
        val first = fixture.runtimeStore.sealForPromotion(
            adoptionEvidenceId = fixture.bundle.adoptionEvidence.id,
            candidateToolId = TOOL_ID,
            readinessEvidenceId = readiness.id,
            expectedReservedInvocations = 5,
            sealedAt = Instant.parse("2026-09-10T15:00:00Z"),
        )
        val repeated = fixture.runtimeStore.sealForPromotion(
            adoptionEvidenceId = fixture.bundle.adoptionEvidence.id,
            candidateToolId = TOOL_ID,
            readinessEvidenceId = readiness.id,
            expectedReservedInvocations = 5,
            sealedAt = Instant.parse("2026-09-10T15:01:00Z"),
        )
        assertEquals(first.id, repeated.id)

        val reserve = fixture.runtimeStore.reserve(
            adoptionEvidenceId = fixture.bundle.adoptionEvidence.id,
            invocationId = "post-seal",
            maxInvocations = 10,
            reservedAt = Instant.now(),
        )
        val sealed = assertIs<EvolutionCanaryReserveResult.Sealed>(reserve)
        assertEquals(first.id, sealed.seal.id)
        assertEquals(5, fixture.runtimeStore.usedInvocations(fixture.bundle.adoptionEvidence.id))
    }

    @Test
    fun `promotion seal rejects changed reservation count instead of racing readiness`() = runTest {
        val fixture = fixture()
        fixture.recordCleanOutcomes(5)
        val readiness = fixture.readinessGate.evaluate(fixture.bundle)
        fixture.reserve("racing-reservation")

        assertFailsWith<IllegalArgumentException> {
            fixture.runtimeStore.sealForPromotion(
                adoptionEvidenceId = fixture.bundle.adoptionEvidence.id,
                candidateToolId = TOOL_ID,
                readinessEvidenceId = readiness.id,
                expectedReservedInvocations = readiness.reservedInvocations,
                sealedAt = Instant.now(),
            )
        }
        assertNull(fixture.runtimeStore.promotionSeal(fixture.bundle.adoptionEvidence.id))
    }

    private inner class Fixture(
        val tools: GeneratedToolRegistry,
        val capabilities: CapabilityRegistry,
        val lifecycle: GeneratedToolLifecycleCoordinator,
        val artifact: CandidateArtifact,
        val bundle: EvolutionCanaryEvidenceBundle,
        val runtimeStore: InMemoryEvolutionCanaryBudgetStore,
        val outcomeStore: InMemoryEvolutionCanaryOutcomeStore,
    ) {
        val outcomeCoordinator = EvolutionCanaryOutcomeCoordinator(runtimeStore, outcomeStore, lifecycle)
        val readinessGate = EvolutionCanaryReadinessGate(runtimeStore, outcomeStore)
        val router = EvolutionCanaryRouter(runtimeStore)
        val bridge = EvolutionPromotionBridge(runtimeStore, outcomeStore, lifecycle)

        suspend fun reserve(invocationId: String): EvolutionCanaryReservation {
            val result = runtimeStore.reserve(
                adoptionEvidenceId = bundle.adoptionEvidence.id,
                invocationId = invocationId,
                maxInvocations = bundle.adoptionRequest.scope.maxInvocations,
                reservedAt = Instant.now(),
            )
            return assertIs<EvolutionCanaryReserveResult.Reserved>(result).reservation
        }

        suspend fun recordCleanOutcome(invocationId: String, reservation: EvolutionCanaryReservation) {
            outcomeCoordinator.record(
                bundle,
                EvolutionCanaryOutcomeInput(
                    reservationId = reservation.id,
                    invocationId = invocationId,
                    success = true,
                    producedExpectedOutput = true,
                    outputFingerprint = "canary-output:$invocationId",
                    latencyMs = 10,
                ),
            )
        }

        suspend fun recordCleanOutcomes(count: Int) {
            repeat(count) { index ->
                val id = "canary-$index"
                recordCleanOutcome(id, reserve(id))
            }
        }

        fun reviewRequest(actorId: String = PROMOTION_ACTOR) = EvolutionPromotionReviewRequest(
            actorId = actorId,
            evidenceRef = "j08:promotion-review:$actorId",
            rationale = "all bounded canary evidence is complete",
            occurredAt = Instant.now(),
        )
    }

    private suspend fun fixture(): Fixture {
        val tools = GeneratedToolRegistry()
        val capabilities = CapabilityRegistry(initialProviders = listOf(baseline()))
        val artifact = candidateArtifact()
        tools.register(verifiedRecord())
        val lifecycle = GeneratedToolLifecycleCoordinator(
            tools = tools,
            capabilityRegistry = capabilities,
        )
        val admitted = lifecycle.admitToTrial(TOOL_ID)
        val trialRecord = when (admitted) {
            is app.lifeos.core.runtime.capability.GeneratedToolTrialAdmissionResult.TrialStarted -> admitted.record
            is app.lifeos.core.runtime.capability.GeneratedToolTrialAdmissionResult.Rejected ->
                error("J08 fixture unexpectedly rejected: ${admitted.reasons}")
        }
        val baseline = baseline()
        val subject = EvolutionSubject.create(artifact, trialRecord, baseline)
        val dataset = EvolutionDatasetRef(
            datasetId = "j08-holdout",
            contentFingerprint = "j08-holdout-fingerprint",
            curatorId = CURATOR_ID,
        )
        val cases = List(5) { index ->
            EvolutionTestCase(
                caseId = "case-$index",
                inputFingerprint = "input-$index",
                expectedOutputFingerprint = "expected-$index",
            )
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
                    recordedAt = artifactTime.plusSeconds(100L + index),
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
                    recordedAt = artifactTime.plusSeconds(200L + index),
                ),
            )
        }
        val report = ShadowEvaluationEngine().evaluate(
            subject = subject,
            dataset = dataset,
            evaluatorId = EVALUATOR_ID,
            cases = cases,
            observations = observations,
        )
        val adoptionRequest = EvolutionAdoptionRequest(
            actorId = ADOPTION_ACTOR,
            evidenceRef = "j08:adoption-review",
            rationale = "bounded canary before final promotion",
            scope = EvolutionCanaryScope(
                assignmentPermille = 100,
                maxInvocations = 10,
                maxDurationSeconds = 3_600,
                allowedTaskTags = setOf(TASK_TAG),
            ),
            occurredAt = Instant.now().minusSeconds(300),
        )
        val adoptionEvidence = EvolutionAdoptionGate().evaluate(
            subject = subject,
            dataset = dataset,
            report = report,
            cases = cases,
            observations = observations,
            currentCandidate = trialRecord,
            currentBaseline = baseline,
            request = adoptionRequest,
        )
        val bundle = EvolutionCanaryEvidenceBundle(
            subject = subject,
            dataset = dataset,
            evaluationReport = report,
            cases = cases,
            observations = observations,
            adoptionEvidence = adoptionEvidence,
            adoptionRequest = adoptionRequest,
            currentCandidate = trialRecord,
            currentBaseline = baseline,
        )
        return Fixture(
            tools = tools,
            capabilities = capabilities,
            lifecycle = lifecycle,
            artifact = artifact,
            bundle = bundle,
            runtimeStore = InMemoryEvolutionCanaryBudgetStore(),
            outcomeStore = InMemoryEvolutionCanaryOutcomeStore(),
        )
    }

    private fun baseline() = CapabilityDescriptor(
        capabilityId = CAPABILITY_ID,
        providerId = BASELINE_ID,
        providerType = ProviderType.MODULE,
        contract = CapabilityContract(
            requiredInputs = setOf("text"),
            outputs = setOf("normalized-text"),
        ),
        state = ProviderState.ACTIVE,
        trustLevel = TrustLevel.SYSTEM,
        reliability = 0.99,
    )

    private fun verifiedRecord() = GeneratedToolRecord(
        manifest = GeneratedToolManifest(
            toolId = TOOL_ID,
            sourceCapability = CAPABILITY_ID,
            sourceHash = "j08-source-hash",
            buildHash = APK_SHA,
            permissions = emptySet(),
            generatedAt = artifactTime,
            requiredInputs = setOf("text"),
            requiredOutputs = setOf("normalized-text"),
        ),
        state = GeneratedToolState.VERIFIED,
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
            summary = "J08 readiness-bound generated tool",
            implementationNotes = listOf("shadow", "canary", "promotion-review"),
            plannedSourcePaths = setOf(SOURCE_PATH),
            plannedTestPaths = setOf(TEST_PATH),
        )
        val patch = SourcePatchPlan(
            designSpecId = design.id,
            operations = listOf(
                SourcePatchOperation(SourcePatchOperationType.CREATE, SOURCE_PATH, "class J08Tool"),
                SourcePatchOperation(SourcePatchOperationType.CREATE, TEST_PATH, "class J08ToolTest"),
            ),
        )
        val commands = BuildGateCommand.entries.map { command ->
            BuildCommandResult(
                command = command,
                success = true,
                exitCode = 0,
                outputFingerprint = "j08:${command.name}",
            )
        }
        val verification = BuildVerificationPolicy().verify(
            BuildVerificationEvidence(
                branchName = BRANCH_NAME,
                branchHeadCommit = APPLIED_HEAD,
                patchPlanId = patch.id,
                commandResults = commands,
                artifact = BuildArtifactEvidence("artifact://j08-debug.apk", APK_SHA),
            )
        )
        val candidate = BuildStudioCandidate(
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
            candidate = candidate,
            verification = verification,
            capabilityChanges = listOf(
                BuildCapabilityChange(
                    capabilityId = CAPABILITY_ID,
                    type = BuildCapabilityChangeType.ADDED,
                    requiredInputs = requirement.requiredInputs,
                    outputs = requirement.requiredOutputs,
                )
            ),
            permissionDelta = BuildPermissionDelta(),
            actors = listOf(
                BuildActorEvidence(
                    actorId = REVIEWER_ACTOR,
                    role = BuildActorRole.REVIEWER,
                    action = BuildActorAction.APPROVED,
                    occurredAt = artifactTime.plusSeconds(5),
                    evidenceRef = "review:j08-approved",
                ),
                BuildActorEvidence(
                    actorId = PROMOTION_ACTOR,
                    role = BuildActorRole.PROMOTION_ACTOR,
                    action = BuildActorAction.PROMOTED,
                    occurredAt = artifactTime.plusSeconds(6),
                    evidenceRef = "promotion:j08-actor",
                ),
            ),
        )
        return CandidateArtifact(candidate, verification, provenance)
    }

    companion object {
        private val CAPABILITY_ID = CapabilityId("text.normalize")
        private const val TOOL_ID = "tool-j08"
        private const val BASELINE_ID = "module-text-normalize-j08"
        private const val EVALUATOR_ID = "evaluator:j08"
        private const val CURATOR_ID = "curator:j08"
        private const val ADOPTION_ACTOR = "adoption:j08"
        private const val REVIEWER_ACTOR = "reviewer:j08"
        private const val PROMOTION_ACTOR = "promotion:j08"
        private const val TASK_TAG = "noncritical-text"
        private const val SOURCE_COMMIT = "f6f7b23f69c592d28e031cd91a276c30996e5cfd"
        private const val APPLIED_HEAD = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        private const val APK_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val BRANCH_NAME = "buildstudio/candidate-j08"
        private const val SOURCE_PREFIX = "core/runtime/src/main/kotlin/app/lifeos/core/runtime/generated"
        private const val TEST_PREFIX = "core/runtime/src/test/kotlin/app/lifeos/core/runtime/generated"
        private const val SOURCE_PATH = "$SOURCE_PREFIX/J08Tool.kt"
        private const val TEST_PATH = "$TEST_PREFIX/J08ToolTest.kt"
    }
}
