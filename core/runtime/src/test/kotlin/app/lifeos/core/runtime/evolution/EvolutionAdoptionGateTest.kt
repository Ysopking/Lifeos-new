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
import app.lifeos.core.runtime.capability.ToolPermission
import app.lifeos.core.runtime.capability.TrustLevel
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EvolutionAdoptionGateTest {
    private val t0 = Instant.parse("2026-09-10T18:00:00Z")

    @Test
    fun `eligible report can be approved only for bounded non activating canary`() {
        val fixture = fixture()
        val request = request()

        val evidence = EvolutionAdoptionGate().evaluate(
            subject = fixture.subject,
            dataset = fixture.dataset,
            report = fixture.report,
            cases = fixture.cases,
            observations = fixture.observations,
            currentCandidate = fixture.candidate,
            currentBaseline = fixture.baseline,
            request = request,
        )

        assertEquals(EvolutionAdoptionDecision.APPROVED_FOR_CANARY, evidence.decision)
        assertEquals(listOf("approved-for-bounded-canary"), evidence.reasons)
        assertEquals(fixture.report.id, evidence.evaluationReportId)
        assertEquals(request.scope.id, evidence.canaryScopeId)
        assertFalse(evidence.activationAllowed)
    }

    @Test
    fun `oversized or productive canary scope is rejected`() {
        val fixture = fixture()
        val request = request(
            scope = EvolutionCanaryScope(
                assignmentPermille = 250,
                maxInvocations = 500,
                maxDurationSeconds = 100_000,
                allowedTaskTags = setOf("text-normalize"),
                productiveEffectsAllowed = true,
            )
        )

        val evidence = EvolutionAdoptionGate().evaluate(
            fixture.subject,
            fixture.dataset,
            fixture.report,
            fixture.cases,
            fixture.observations,
            fixture.candidate,
            fixture.baseline,
            request,
        )

        assertEquals(EvolutionAdoptionDecision.REJECTED, evidence.decision)
        assertTrue(evidence.reasons.any { it.startsWith("assignment-scope-too-large:") })
        assertTrue(evidence.reasons.any { it.startsWith("invocation-budget-too-large:") })
        assertTrue(evidence.reasons.any { it.startsWith("duration-too-large:") })
        assertTrue(evidence.reasons.contains("productive-effects-forbidden-in-initial-canary"))
    }

    @Test
    fun `side effecting candidate permissions cannot enter initial canary`() {
        val fixture = fixture(permissions = setOf(ToolPermission.NETWORK_ACCESS, ToolPermission.DATABASE_WRITE))

        val evidence = EvolutionAdoptionGate().evaluate(
            fixture.subject,
            fixture.dataset,
            fixture.report,
            fixture.cases,
            fixture.observations,
            fixture.candidate,
            fixture.baseline,
            request(),
        )

        assertEquals(EvolutionAdoptionDecision.REJECTED, evidence.decision)
        assertEquals(
            listOf("forbidden-canary-permissions:DATABASE_WRITE,NETWORK_ACCESS"),
            evidence.reasons,
        )
    }

    @Test
    fun `candidate or baseline mutation invalidates adoption evidence`() {
        val fixture = fixture()

        assertFailsWith<IllegalArgumentException> {
            EvolutionAdoptionGate().evaluate(
                fixture.subject,
                fixture.dataset,
                fixture.report,
                fixture.cases,
                fixture.observations,
                fixture.candidate.copy(lastMessage = "changed-after-evaluation"),
                fixture.baseline,
                request(),
            )
        }

        assertFailsWith<IllegalArgumentException> {
            EvolutionAdoptionGate().evaluate(
                fixture.subject,
                fixture.dataset,
                fixture.report,
                fixture.cases,
                fixture.observations,
                fixture.candidate,
                fixture.baseline.copy(reliability = 0.5),
                request(),
            )
        }
    }

    @Test
    fun `adoption actor must be separated from evaluator curator and candidate`() {
        val fixture = fixture()
        listOf(EVALUATOR_ID, CURATOR_ID, TOOL_ID).forEach { actor ->
            assertFailsWith<IllegalArgumentException> {
                EvolutionAdoptionGate().evaluate(
                    fixture.subject,
                    fixture.dataset,
                    fixture.report,
                    fixture.cases,
                    fixture.observations,
                    fixture.candidate,
                    fixture.baseline,
                    request(actorId = actor),
                )
            }
        }
    }

    @Test
    fun `report from lenient foreign policy cannot pass trusted replay`() {
        val fixture = fixture()
        val weakerObservations = pairedObservations(
            fixture.cases,
            baselineQuality = 0.90,
            candidateQuality = 0.90,
        )
        val lenientReport = ShadowEvaluationEngine(
            EvolutionEvaluationPolicy(
                minimumCases = 1,
                minimumCandidateSuccessRate = 0.0,
                minimumCandidateMeanQuality = 0.0,
                minimumQualityDelta = 0.0,
                maximumLatencyRatio = 100.0,
                maximumPeakMemoryRatio = 100.0,
            )
        ).evaluate(
            fixture.subject,
            fixture.dataset,
            EVALUATOR_ID,
            fixture.cases,
            weakerObservations,
        )
        assertEquals(EvolutionEvaluationDecision.ELIGIBLE, lenientReport.decision)

        assertFailsWith<IllegalArgumentException> {
            EvolutionAdoptionGate().evaluate(
                fixture.subject,
                fixture.dataset,
                lenientReport,
                fixture.cases,
                weakerObservations,
                fixture.candidate,
                fixture.baseline,
                request(),
            )
        }
    }

    @Test
    fun `trusted but non eligible evaluation produces explicit rejected adoption`() {
        val cases = cases(5)
        val candidate = candidate()
        val baseline = baseline()
        val subject = EvolutionSubject.create(candidateArtifact(), candidate, baseline)
        val dataset = dataset()
        val observations = pairedObservations(cases, baselineQuality = 0.95, candidateQuality = 0.95)
        val report = ShadowEvaluationEngine().evaluate(subject, dataset, EVALUATOR_ID, cases, observations)
        assertEquals(EvolutionEvaluationDecision.NOT_BETTER, report.decision)

        val evidence = EvolutionAdoptionGate().evaluate(
            subject,
            dataset,
            report,
            cases,
            observations,
            candidate,
            baseline,
            request(),
        )

        assertEquals(EvolutionAdoptionDecision.REJECTED, evidence.decision)
        assertEquals(listOf("evaluation-not-eligible:NOT_BETTER"), evidence.reasons)
    }

    @Test
    fun `adoption evidence identity is deterministic for exact evidence`() {
        val fixture = fixture()
        val gate = EvolutionAdoptionGate()
        val request = request()

        val first = gate.evaluate(
            fixture.subject, fixture.dataset, fixture.report, fixture.cases,
            fixture.observations, fixture.candidate, fixture.baseline, request,
        )
        val second = gate.evaluate(
            fixture.subject, fixture.dataset, fixture.report, fixture.cases.reversed(),
            fixture.observations.reversed(), fixture.candidate, fixture.baseline, request,
        )

        assertEquals(first.id, second.id)
    }

    private data class Fixture(
        val candidate: GeneratedToolRecord,
        val baseline: CapabilityDescriptor,
        val subject: EvolutionSubject,
        val dataset: EvolutionDatasetRef,
        val cases: List<EvolutionTestCase>,
        val observations: List<EvolutionShadowObservation>,
        val report: EvolutionEvaluationReport,
    )

    private fun fixture(permissions: Set<ToolPermission> = emptySet()): Fixture {
        val candidate = candidate(permissions)
        val baseline = baseline()
        val subject = EvolutionSubject.create(candidateArtifact(permissions), candidate, baseline)
        val dataset = dataset()
        val cases = cases(5)
        val observations = pairedObservations(cases, baselineQuality = 0.80, candidateQuality = 0.95)
        val report = ShadowEvaluationEngine().evaluate(subject, dataset, EVALUATOR_ID, cases, observations)
        return Fixture(candidate, baseline, subject, dataset, cases, observations, report)
    }

    private fun request(
        actorId: String = ADOPTION_ACTOR_ID,
        scope: EvolutionCanaryScope = EvolutionCanaryScope(
            assignmentPermille = 50,
            maxInvocations = 25,
            maxDurationSeconds = 3_600,
            allowedTaskTags = setOf("text-normalize"),
        ),
    ) = EvolutionAdoptionRequest(
        actorId = actorId,
        evidenceRef = "adoption-review:j05",
        rationale = "independent shadow result qualifies for bounded canary",
        scope = scope,
        occurredAt = t0.plusSeconds(500),
    )

    private fun dataset() = EvolutionDatasetRef(
        datasetId = "j05-holdout-v1",
        contentFingerprint = "holdout-content-fingerprint",
        curatorId = CURATOR_ID,
    )

    private fun cases(count: Int): List<EvolutionTestCase> = List(count) { index ->
        EvolutionTestCase(
            caseId = "case-$index",
            inputFingerprint = "input-$index",
            expectedOutputFingerprint = "expected-$index",
        )
    }

    private fun pairedObservations(
        cases: List<EvolutionTestCase>,
        baselineQuality: Double,
        candidateQuality: Double,
    ): List<EvolutionShadowObservation> = cases.flatMapIndexed { index, case ->
        listOf(
            EvolutionShadowObservation(
                caseId = case.caseId,
                side = EvolutionObservationSide.BASELINE,
                providerId = BASELINE_ID,
                success = true,
                outputFingerprint = "baseline-$index",
                qualityScore = baselineQuality,
                latencyMs = 10,
                peakMemoryBytes = 1_000,
                recordedAt = t0.plusSeconds(index.toLong()),
            ),
            EvolutionShadowObservation(
                caseId = case.caseId,
                side = EvolutionObservationSide.CANDIDATE,
                providerId = TOOL_ID,
                success = true,
                outputFingerprint = case.expectedOutputFingerprint,
                qualityScore = candidateQuality,
                latencyMs = 10,
                peakMemoryBytes = 1_000,
                recordedAt = t0.plusSeconds(100L + index),
            ),
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

    private fun candidate(permissions: Set<ToolPermission> = emptySet()) = GeneratedToolRecord(
        manifest = GeneratedToolManifest(
            toolId = TOOL_ID,
            sourceCapability = CAPABILITY_ID,
            sourceHash = "j05-source-hash",
            buildHash = APK_SHA,
            permissions = permissions,
            generatedAt = t0,
            requiredInputs = setOf("text"),
            requiredOutputs = setOf("normalized-text"),
        ),
        state = GeneratedToolState.TRIAL,
        verificationConfidence = 0.96,
    )

    private fun candidateArtifact(permissions: Set<ToolPermission> = emptySet()): CandidateArtifact {
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
            summary = "J05 candidate",
            implementationNotes = listOf("bounded", "adoption-gated"),
            plannedSourcePaths = setOf(SOURCE_PATH),
            plannedTestPaths = setOf(TEST_PATH),
        )
        val patch = SourcePatchPlan(
            designSpecId = design.id,
            operations = listOf(
                SourcePatchOperation(SourcePatchOperationType.CREATE, SOURCE_PATH, "class J05Candidate"),
                SourcePatchOperation(SourcePatchOperationType.CREATE, TEST_PATH, "class J05CandidateTest"),
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
                artifact = BuildArtifactEvidence("artifact://j05-debug.apk", APK_SHA),
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
            permissionDelta = app.lifeos.core.runtime.buildstudio.BuildPermissionDelta(added = permissions),
        )
        return CandidateArtifact(buildCandidate, verification, provenance)
    }

    companion object {
        private val CAPABILITY_ID = CapabilityId("text.normalize")
        private const val TOOL_ID = "tool-j05"
        private const val BASELINE_ID = "module-text-normalize-v1"
        private const val EVALUATOR_ID = "evaluator:independent-j05"
        private const val CURATOR_ID = "curator:independent-j05"
        private const val ADOPTION_ACTOR_ID = "adoption:local-user"
        private const val SOURCE_COMMIT = "803cef16d86d93b8332e559168afa896aca5879d"
        private const val APPLIED_HEAD = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        private const val APK_SHA = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        private const val BRANCH_NAME = "buildstudio/candidate-j05"
        private const val SOURCE_PREFIX = "core/runtime/src/main/kotlin/app/lifeos/core/runtime/generated"
        private const val TEST_PREFIX = "core/runtime/src/test/kotlin/app/lifeos/core/runtime/generated"
        private const val SOURCE_PATH = "$SOURCE_PREFIX/J05Candidate.kt"
        private const val TEST_PATH = "$TEST_PREFIX/J05CandidateTest.kt"
    }
}
