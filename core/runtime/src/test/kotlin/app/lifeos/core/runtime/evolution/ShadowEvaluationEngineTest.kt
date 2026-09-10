package app.lifeos.core.runtime.evolution

import app.lifeos.core.runtime.buildstudio.BuildArtifactEvidence
import app.lifeos.core.runtime.buildstudio.BuildCapabilityChange
import app.lifeos.core.runtime.buildstudio.BuildCapabilityChangeType
import app.lifeos.core.runtime.buildstudio.BuildCommandResult
import app.lifeos.core.runtime.buildstudio.BuildDesignSpec
import app.lifeos.core.runtime.buildstudio.BuildGateCommand
import app.lifeos.core.runtime.buildstudio.BuildPathPolicy
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShadowEvaluationEngineTest {
    private val t0 = Instant.parse("2026-09-10T16:00:00Z")

    @Test
    fun `better candidate on frozen independent holdout becomes eligible but never activating`() {
        val subject = subject()
        val cases = cases(5)
        val observations = pairedObservations(cases, baselineQuality = 0.80, candidateQuality = 0.95)

        val report = ShadowEvaluationEngine().evaluate(
            subject = subject,
            dataset = dataset(),
            evaluatorId = EVALUATOR_ID,
            cases = cases,
            observations = observations,
        )

        assertEquals(EvolutionEvaluationDecision.ELIGIBLE, report.decision)
        assertEquals(listOf("candidate-meets-independent-shadow-policy"), report.reasons)
        assertEquals(5, report.baselineStats.cases)
        assertEquals(5, report.candidateStats.cases)
        assertEquals(1.0, report.candidateStats.successRate)
        assertTrue(report.candidateStats.meanQuality > report.baselineStats.meanQuality)
        assertTrue(report.candidateStats.successWilsonLower95 in 0.0..1.0)
        assertFalse(report.activationAllowed)
        assertFalse(subject.activationAllowed)
    }

    @Test
    fun `small improvement is not enough for adoption`() {
        val cases = cases(5)
        val report = ShadowEvaluationEngine().evaluate(
            subject = subject(),
            dataset = dataset(),
            evaluatorId = EVALUATOR_ID,
            cases = cases,
            observations = pairedObservations(cases, baselineQuality = 0.93, candidateQuality = 0.94),
        )

        assertEquals(EvolutionEvaluationDecision.NOT_BETTER, report.decision)
        assertTrue(report.reasons.any { it.startsWith("quality-delta:") })
        assertFalse(report.activationAllowed)
    }

    @Test
    fun `candidate hard failure rejects even when aggregate quality is high`() {
        val cases = cases(5)
        val observations = pairedObservations(cases, baselineQuality = 0.80, candidateQuality = 0.99).toMutableList()
        val index = observations.indexOfFirst {
            it.side == EvolutionObservationSide.CANDIDATE && it.caseId == "case-2"
        }
        observations[index] = observations[index].copy(
            hardFailures = setOf(EvolutionHardFailure.SAFETY_VIOLATION),
        )

        val report = ShadowEvaluationEngine().evaluate(
            subject(), dataset(), EVALUATOR_ID, cases, observations,
        )

        assertEquals(EvolutionEvaluationDecision.REJECTED, report.decision)
        assertEquals(listOf("hard-failure:SAFETY_VIOLATION"), report.reasons)
    }

    @Test
    fun `productive effect attempt is a hard rejection and cannot be hidden`() {
        val cases = cases(5)
        val observations = pairedObservations(cases, baselineQuality = 0.80, candidateQuality = 0.99).toMutableList()
        val index = observations.indexOfFirst {
            it.side == EvolutionObservationSide.CANDIDATE && it.caseId == "case-1"
        }
        observations[index] = observations[index].copy(
            productiveEffectAttempted = true,
            hardFailures = setOf(EvolutionHardFailure.UNAUTHORIZED_EFFECT),
        )

        val report = ShadowEvaluationEngine().evaluate(
            subject(), dataset(), EVALUATOR_ID, cases, observations,
        )

        assertEquals(EvolutionEvaluationDecision.REJECTED, report.decision)
        assertTrue(report.reasons.contains("hard-failure:UNAUTHORIZED_EFFECT"))
    }

    @Test
    fun `exact expected output mismatch is a hard rejection regardless of supplied quality`() {
        val cases = cases(5)
        val observations = pairedObservations(cases, baselineQuality = 0.80, candidateQuality = 1.0).toMutableList()
        val index = observations.indexOfFirst {
            it.side == EvolutionObservationSide.CANDIDATE && it.caseId == "case-3"
        }
        observations[index] = observations[index].copy(outputFingerprint = "wrong-output")

        val report = ShadowEvaluationEngine().evaluate(
            subject(), dataset(), EVALUATOR_ID, cases, observations,
        )

        assertEquals(EvolutionEvaluationDecision.REJECTED, report.decision)
        assertEquals(listOf("expected-output-mismatch:case-3"), report.reasons)
    }

    @Test
    fun `too few holdout cases is explicit insufficient evidence`() {
        val cases = cases(3)
        val report = ShadowEvaluationEngine().evaluate(
            subject(), dataset(), EVALUATOR_ID, cases,
            pairedObservations(cases, baselineQuality = 0.70, candidateQuality = 1.0),
        )

        assertEquals(EvolutionEvaluationDecision.INSUFFICIENT_EVIDENCE, report.decision)
        assertEquals(listOf("insufficient-cases:3<5"), report.reasons)
    }

    @Test
    fun `candidate cannot curate holdout or evaluate itself`() {
        val cases = cases(5)
        val observations = pairedObservations(cases, baselineQuality = 0.80, candidateQuality = 0.95)

        assertFailsWith<IllegalArgumentException> {
            ShadowEvaluationEngine().evaluate(
                subject(), dataset(curatorId = TOOL_ID), EVALUATOR_ID, cases, observations,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ShadowEvaluationEngine().evaluate(
                subject(), dataset(), TOOL_ID, cases, observations,
            )
        }
    }

    @Test
    fun `paired evidence must be complete unique and provider bound`() {
        val cases = cases(5)
        val observations = pairedObservations(cases, baselineQuality = 0.80, candidateQuality = 0.95)

        assertFailsWith<IllegalArgumentException> {
            ShadowEvaluationEngine().evaluate(
                subject(), dataset(), EVALUATOR_ID, cases, observations.dropLast(1),
            )
        }

        val wrongProvider = observations.map {
            if (it.side == EvolutionObservationSide.CANDIDATE && it.caseId == "case-0") {
                it.copy(providerId = "foreign-candidate")
            } else it
        }
        assertFailsWith<IllegalArgumentException> {
            ShadowEvaluationEngine().evaluate(
                subject(), dataset(), EVALUATOR_ID, cases, wrongProvider,
            )
        }
    }

    @Test
    fun `report identity is stable under observation and case ordering`() {
        val subject = subject()
        val cases = cases(5)
        val observations = pairedObservations(cases, baselineQuality = 0.80, candidateQuality = 0.95)
        val engine = ShadowEvaluationEngine()

        val first = engine.evaluate(subject, dataset(), EVALUATOR_ID, cases, observations)
        val second = engine.evaluate(subject, dataset(), EVALUATOR_ID, cases.reversed(), observations.reversed())

        assertEquals(first.id, second.id)
        assertEquals(first.caseEvidenceIds, second.caseEvidenceIds)
        assertEquals(first.observationEvidenceIds, second.observationEvidenceIds)
    }

    @Test
    fun `slower or more memory hungry candidate is not better despite quality`() {
        val cases = cases(5)
        val observations = pairedObservations(
            cases = cases,
            baselineQuality = 0.80,
            candidateQuality = 0.99,
            baselineLatencyMs = 10,
            candidateLatencyMs = 20,
            baselineMemoryBytes = 1_000,
            candidateMemoryBytes = 2_000,
        )

        val report = ShadowEvaluationEngine().evaluate(
            subject(), dataset(), EVALUATOR_ID, cases, observations,
        )

        assertEquals(EvolutionEvaluationDecision.NOT_BETTER, report.decision)
        assertTrue(report.reasons.any { it.startsWith("latency-ratio:") })
        assertTrue(report.reasons.any { it.startsWith("memory-ratio:") })
    }

    @Test
    fun `subject is bound to trial artifact and exact baseline contract`() {
        val fixture = candidateFixture()
        val candidate = verifiedTrialRecord()

        assertFailsWith<IllegalArgumentException> {
            EvolutionSubject.create(
                artifact = fixture,
                candidate = candidate.copy(state = GeneratedToolState.VERIFIED),
                baseline = baseline(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            EvolutionSubject.create(
                artifact = fixture,
                candidate = candidate,
                baseline = baseline(providerId = TOOL_ID),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            EvolutionSubject.create(
                artifact = fixture,
                candidate = candidate,
                baseline = baseline(outputs = setOf("other-output")),
            )
        }
    }

    @Test
    fun `evolution trust root cannot be patched by BuildStudio candidate`() {
        assertTrue(
            BuildPathPolicy().isProtected(
                "core/runtime/src/main/kotlin/app/lifeos/core/runtime/evolution/ShadowEvaluationEngine.kt"
            )
        )
        assertTrue(
            BuildPathPolicy().isProtected(
                "core/runtime/src/main/kotlin/app/lifeos/core/runtime/evolution/EvolutionModels.kt"
            )
        )
    }

    private fun subject(): EvolutionSubject = EvolutionSubject.create(
        artifact = candidateFixture(),
        candidate = verifiedTrialRecord(),
        baseline = baseline(),
    )

    private fun dataset(curatorId: String = CURATOR_ID) = EvolutionDatasetRef(
        datasetId = "holdout-text-normalize-v1",
        contentFingerprint = "holdout-fingerprint-v1",
        curatorId = curatorId,
    )

    private fun cases(count: Int): List<EvolutionTestCase> = List(count) { index ->
        EvolutionTestCase(
            caseId = "case-$index",
            inputFingerprint = "input-fingerprint-$index",
            expectedOutputFingerprint = "expected-fingerprint-$index",
        )
    }

    private fun pairedObservations(
        cases: List<EvolutionTestCase>,
        baselineQuality: Double,
        candidateQuality: Double,
        baselineLatencyMs: Long = 10,
        candidateLatencyMs: Long = 10,
        baselineMemoryBytes: Long = 1_000,
        candidateMemoryBytes: Long = 1_000,
    ): List<EvolutionShadowObservation> = cases.flatMapIndexed { index, case ->
        listOf(
            EvolutionShadowObservation(
                caseId = case.caseId,
                side = EvolutionObservationSide.BASELINE,
                providerId = BASELINE_ID,
                success = true,
                outputFingerprint = "baseline-output-$index",
                qualityScore = baselineQuality,
                latencyMs = baselineLatencyMs,
                peakMemoryBytes = baselineMemoryBytes,
                recordedAt = t0.plusSeconds(index.toLong()),
            ),
            EvolutionShadowObservation(
                caseId = case.caseId,
                side = EvolutionObservationSide.CANDIDATE,
                providerId = TOOL_ID,
                success = true,
                outputFingerprint = case.expectedOutputFingerprint ?: "candidate-output-$index",
                qualityScore = candidateQuality,
                latencyMs = candidateLatencyMs,
                peakMemoryBytes = candidateMemoryBytes,
                recordedAt = t0.plusSeconds(100L + index),
            ),
        )
    }

    private fun baseline(
        providerId: String = BASELINE_ID,
        outputs: Set<String> = setOf("normalized-text"),
    ) = CapabilityDescriptor(
        capabilityId = CAPABILITY_ID,
        providerId = providerId,
        providerType = ProviderType.MODULE,
        contract = CapabilityContract(
            requiredInputs = setOf("text"),
            outputs = outputs,
        ),
        state = ProviderState.ACTIVE,
        trustLevel = TrustLevel.SYSTEM,
        reliability = 0.99,
    )

    private fun verifiedTrialRecord() = GeneratedToolRecord(
        manifest = GeneratedToolManifest(
            toolId = TOOL_ID,
            sourceCapability = CAPABILITY_ID,
            sourceHash = "candidate-source-hash",
            buildHash = APK_SHA,
            permissions = emptySet(),
            generatedAt = t0,
            requiredInputs = setOf("text"),
            requiredOutputs = setOf("normalized-text"),
        ),
        state = GeneratedToolState.TRIAL,
        verificationConfidence = 0.96,
    )

    private fun candidateFixture(): CandidateArtifact {
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
            summary = "J04 candidate",
            implementationNotes = listOf("bounded", "shadow-evaluated"),
            plannedSourcePaths = setOf(SOURCE_PATH),
            plannedTestPaths = setOf(TEST_PATH),
        )
        val patch = SourcePatchPlan(
            designSpecId = design.id,
            operations = listOf(
                SourcePatchOperation(SourcePatchOperationType.CREATE, SOURCE_PATH, "class Candidate"),
                SourcePatchOperation(SourcePatchOperationType.CREATE, TEST_PATH, "class CandidateTest"),
            ),
        )
        val commands = BuildGateCommand.entries.map { command ->
            BuildCommandResult(
                command = command,
                success = true,
                exitCode = 0,
                outputFingerprint = "gate:${command.name}",
            )
        }
        val verification = BuildVerificationPolicy().verify(
            BuildVerificationEvidence(
                branchName = BRANCH_NAME,
                branchHeadCommit = APPLIED_HEAD,
                patchPlanId = patch.id,
                commandResults = commands,
                artifact = BuildArtifactEvidence("artifact://j04-debug.apk", APK_SHA),
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
        )
        return CandidateArtifact(candidate, verification, provenance)
    }

    companion object {
        private val CAPABILITY_ID = CapabilityId("text.normalize")
        private const val TOOL_ID = "tool-j04"
        private const val BASELINE_ID = "module-text-normalize-v1"
        private const val EVALUATOR_ID = "evaluator:independent-v1"
        private const val CURATOR_ID = "curator:holdout-v1"
        private const val SOURCE_COMMIT = "027e0b572484be2e60115e0426a1b089ee0f76aa"
        private const val APPLIED_HEAD = "dddddddddddddddddddddddddddddddddddddddd"
        private const val APK_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val BRANCH_NAME = "buildstudio/candidate-j04"
        private const val SOURCE_PREFIX = "core/runtime/src/main/kotlin/app/lifeos/core/runtime/generated"
        private const val TEST_PREFIX = "core/runtime/src/test/kotlin/app/lifeos/core/runtime/generated"
        private const val SOURCE_PATH = "$SOURCE_PREFIX/J04Candidate.kt"
        private const val TEST_PATH = "$TEST_PREFIX/J04CandidateTest.kt"
    }
}
