package app.lifeos.core.runtime.capability

import app.lifeos.core.field.FieldSnapshotId
import app.lifeos.core.field.StableFieldIds
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
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GeneratedToolPromotionEvidenceTest {
    private val t0 = Instant.parse("2026-09-10T12:30:00Z")

    @Test
    fun `clean trials alone cannot promote without build and field evidence`() = runTest {
        val fixture = Fixture()
        fixture.enterTrial()
        fixture.recordCleanTrials()

        val evaluation = assertIs<GeneratedToolPromotionEvaluation.NotReady>(
            fixture.lifecycle.evaluatePromotion(TOOL_ID)
        )

        assertTrue("missing-build-provenance" in evaluation.reasons)
        assertTrue(evaluation.reasons.any { it.startsWith("insufficient-field-snapshots:") })
        assertEquals(3, evaluation.evidence.trialOutcomes.size)
    }

    @Test
    fun `complete evidence promotes and registry binds exact promotion snapshot`() = runTest {
        val fixture = Fixture()
        fixture.enterTrial()
        fixture.bindRequiredEvidence()
        fixture.recordCleanTrials()

        val evaluation = assertIs<GeneratedToolPromotionEvaluation.Eligible>(
            fixture.lifecycle.evaluatePromotion(TOOL_ID)
        )
        assertNotNull(evaluation.evidence.buildEvidence)
        assertEquals(setOf(FieldSnapshotId("snapshot:j03-design")), evaluation.evidence.fieldSnapshotIds)

        val promoted = fixture.lifecycle.promote(TOOL_ID)

        assertEquals(GeneratedToolState.ACTIVE, promoted.state)
        assertEquals(evaluation.evidence.id, fixture.tools.promotionEvidenceId(TOOL_ID))
        assertTrue(promoted.lastMessage?.contains(evaluation.evidence.id) == true)
    }

    @Test
    fun `unresolved degraded health incident blocks otherwise eligible tool`() = runTest {
        val fixture = Fixture()
        fixture.enterTrial()
        fixture.bindRequiredEvidence()
        fixture.recordCleanTrials()
        val incident = healthIncident()
        fixture.lifecycle.recordHealthIncident(TOOL_ID, incident)

        val evaluation = assertIs<GeneratedToolPromotionEvaluation.NotReady>(
            fixture.lifecycle.evaluatePromotion(TOOL_ID)
        )

        assertTrue("unresolved-health-incidents:1" in evaluation.reasons)
    }

    @Test
    fun `health incident requires explicit resolution evidence before eligibility returns`() = runTest {
        val fixture = Fixture()
        fixture.enterTrial()
        fixture.bindRequiredEvidence()
        fixture.recordCleanTrials()
        val incident = healthIncident()
        fixture.lifecycle.recordHealthIncident(TOOL_ID, incident)
        assertIs<GeneratedToolPromotionEvaluation.NotReady>(fixture.lifecycle.evaluatePromotion(TOOL_ID))

        fixture.lifecycle.resolveHealthIncident(
            toolId = TOOL_ID,
            incident = incident,
            resolutionEvidenceRef = "repair-probe:healthy",
        )

        assertIs<GeneratedToolPromotionEvaluation.Eligible>(
            fixture.lifecycle.evaluatePromotion(TOOL_ID)
        )
    }

    @Test
    fun `rollback history blocks repromotion by default`() = runTest {
        val fixture = Fixture()
        fixture.enterTrial()
        fixture.bindRequiredEvidence()
        fixture.recordCleanTrials()
        fixture.lifecycle.recordRollback(
            TOOL_ID,
            GeneratedToolRollbackEvidence(
                sourceCandidateId = fixture.artifact.id,
                reasonFingerprint = "regression",
                occurredAt = t0.plusSeconds(20),
            ),
        )

        val evaluation = assertIs<GeneratedToolPromotionEvaluation.NotReady>(
            fixture.lifecycle.evaluatePromotion(TOOL_ID)
        )

        assertTrue("rollback-count:1>0" in evaluation.reasons)
    }

    @Test
    fun `same trial invocation cannot be rewritten with conflicting outcome`() = runTest {
        val fixture = Fixture()
        fixture.enterTrial()
        val first = GeneratedToolTrialResult(
            invocationId = "same-invocation",
            success = true,
            producedExpectedOutput = true,
            latencyMs = 5,
            recordedAt = t0,
        )
        fixture.lifecycle.recordTrial(TOOL_ID, first)

        assertFailsWith<IllegalArgumentException> {
            fixture.lifecycle.recordTrial(
                TOOL_ID,
                first.copy(success = false, producedExpectedOutput = false),
            )
        }
    }

    @Test
    fun `generic registry transition cannot bypass promotion evidence`() = runTest {
        val fixture = Fixture()
        fixture.enterTrial()

        assertFailsWith<IllegalArgumentException> {
            fixture.tools.transition(TOOL_ID, GeneratedToolState.ACTIVE)
        }
        assertEquals(GeneratedToolState.TRIAL, fixture.tools.get(TOOL_ID)?.state)
    }

    @Test
    fun `promotion freezes evidence against late mutation`() = runTest {
        val fixture = Fixture()
        fixture.enterTrial()
        fixture.bindRequiredEvidence()
        fixture.recordCleanTrials()
        fixture.lifecycle.promote(TOOL_ID)

        assertFailsWith<IllegalArgumentException> {
            fixture.lifecycle.recordHealthIncident(
                TOOL_ID,
                healthIncident().copy(occurredAt = t0.plusSeconds(40)),
            )
        }
    }

    @Test
    fun `build artifact with different permission contract cannot bind`() = runTest {
        val fixture = Fixture(permissions = setOf(ToolPermission.WRITE_TEMP_FILE))
        fixture.enterTrial()
        val artifactWithoutPermission = buildArtifact(
            requiredInputs = fixture.record.manifest.requiredInputs,
            requiredOutputs = fixture.record.manifest.requiredOutputs,
            permissions = emptySet(),
        )

        assertFailsWith<IllegalArgumentException> {
            fixture.lifecycle.bindBuildArtifact(TOOL_ID, artifactWithoutPermission)
        }
    }

    @Test
    fun `build artifact with different source cannot bind even with same capability and permissions`() = runTest {
        val fixture = Fixture()
        fixture.enterTrial()
        val differentSource = buildArtifact(
            requiredInputs = fixture.record.manifest.requiredInputs,
            requiredOutputs = fixture.record.manifest.requiredOutputs,
            permissions = fixture.record.manifest.permissions,
            sourceContent = "class OtherGeneratedNormalizer",
        )

        assertFailsWith<IllegalArgumentException> {
            fixture.lifecycle.bindBuildArtifact(TOOL_ID, differentSource)
        }
    }

    private fun healthIncident() = GeneratedToolHealthIncident(
        sourceNodeId = "worker:$TOOL_ID",
        severity = GeneratedToolHealthSeverity.DEGRADED,
        messageFingerprint = "health-degraded",
        occurredAt = t0.plusSeconds(10),
    )

    private inner class Fixture(
        permissions: Set<ToolPermission> = emptySet(),
    ) {
        val tools = GeneratedToolRegistry()
        val record = verifiedRecord(permissions)
        val ledger = GeneratedToolPromotionEvidenceLedger()
        val lifecycle = GeneratedToolLifecycleCoordinator(
            tools = tools,
            promotionEvidenceLedger = ledger,
        )
        val artifact = buildArtifact(
            requiredInputs = record.manifest.requiredInputs,
            requiredOutputs = record.manifest.requiredOutputs,
            permissions = record.manifest.permissions,
        )

        suspend fun enterTrial() {
            tools.register(record)
            assertIs<GeneratedToolTrialAdmissionResult.TrialStarted>(lifecycle.admitToTrial(TOOL_ID))
        }

        suspend fun bindRequiredEvidence() {
            lifecycle.bindDesignFieldSnapshots(
                TOOL_ID,
                setOf(FieldSnapshotId("snapshot:j03-design")),
            )
            lifecycle.bindBuildArtifact(TOOL_ID, artifact)
        }

        suspend fun recordCleanTrials() {
            repeat(3) { index ->
                lifecycle.recordTrial(
                    TOOL_ID,
                    GeneratedToolTrialResult(
                        invocationId = "trial-$index",
                        success = true,
                        producedExpectedOutput = true,
                        latencyMs = 10,
                        recordedAt = t0.plusSeconds(index.toLong()),
                    ),
                )
            }
        }
    }

    private fun verifiedRecord(permissions: Set<ToolPermission>) = GeneratedToolRecord(
        manifest = GeneratedToolManifest(
            toolId = TOOL_ID,
            sourceCapability = CapabilityId(CAPABILITY_ID),
            sourceHash = "source-hash",
            buildHash = "build-hash",
            permissions = permissions,
            generatedAt = t0,
            requiredInputs = setOf("text"),
            requiredOutputs = setOf("normalized-text"),
            sourceContentFingerprint = StableFieldIds.fingerprint(
                "source-patch-content/v1",
                GENERATED_SOURCE_CONTENT,
            ),
        ),
        state = GeneratedToolState.VERIFIED,
        verificationConfidence = 0.95,
    )

    private fun buildArtifact(
        requiredInputs: Set<String>,
        requiredOutputs: Set<String>,
        permissions: Set<ToolPermission>,
        sourceContent: String = GENERATED_SOURCE_CONTENT,
    ): CandidateArtifact {
        val requirement = CapabilityRequirement(
            capabilityId = CapabilityId(CAPABILITY_ID),
            requiredInputs = requiredInputs,
            requiredOutputs = requiredOutputs,
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
            summary = "generated normalization tool",
            implementationNotes = listOf("bounded"),
            plannedSourcePaths = setOf(SOURCE_PATH),
            plannedTestPaths = setOf(TEST_PATH),
        )
        val patch = SourcePatchPlan(
            designSpecId = design.id,
            operations = listOf(
                SourcePatchOperation(SourcePatchOperationType.CREATE, SOURCE_PATH, sourceContent),
                SourcePatchOperation(SourcePatchOperationType.CREATE, TEST_PATH, "class GeneratedNormalizerTest"),
            ),
        )
        val verificationEvidence = BuildVerificationEvidence(
            branchName = "buildstudio/candidate-j03",
            branchHeadCommit = BUILD_HEAD,
            patchPlanId = patch.id,
            commandResults = BuildGateCommand.entries.map { command ->
                BuildCommandResult(
                    command = command,
                    success = true,
                    exitCode = 0,
                    outputFingerprint = "output-${command.name}",
                )
            },
            artifact = BuildArtifactEvidence(
                debugApkRef = "artifact://j03-debug.apk",
                debugApkSha256 = "c".repeat(64),
            ),
        )
        val verification = BuildVerificationPolicy().verify(verificationEvidence)
        val candidate = BuildStudioCandidate(
            buildSpecId = spec.id,
            designSpecId = design.id,
            patchPlanId = patch.id,
            branchName = verificationEvidence.branchName,
            branchHeadCommit = BUILD_HEAD,
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
                    capabilityId = requirement.capabilityId,
                    type = BuildCapabilityChangeType.ADDED,
                    requiredInputs = requiredInputs,
                    outputs = requiredOutputs,
                )
            ),
            permissionDelta = BuildPermissionDelta(added = permissions),
        )
        return CandidateArtifact(candidate, verification, provenance)
    }

    private companion object {
        const val TOOL_ID = "tool-1"
        const val CAPABILITY_ID = "text.normalize"
        const val SOURCE_COMMIT = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val BUILD_HEAD = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val SOURCE_PREFIX = "core/runtime/src/main/kotlin/app/lifeos/core/runtime/generated"
        const val TEST_PREFIX = "core/runtime/src/test/kotlin/app/lifeos/core/runtime/generated"
        const val SOURCE_PATH = "$SOURCE_PREFIX/GeneratedNormalizer.kt"
        const val TEST_PATH = "$TEST_PREFIX/GeneratedNormalizerTest.kt"
        const val GENERATED_SOURCE_CONTENT = "class GeneratedNormalizer"
    }
}
