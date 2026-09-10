package app.lifeos.core.runtime.capability

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
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GeneratedToolPromotionEvidenceTest {
    private val t0 = Instant.parse("2026-09-10T12:00:00Z")

    @Test
    fun `exact candidate trial and separated actors permit explicit promotion`() = runTest {
        val tools = GeneratedToolRegistry()
        val capabilities = CapabilityRegistry()
        val artifact = candidateArtifact()
        tools.register(verifiedRecord())
        val lifecycle = GeneratedToolLifecycleCoordinator(
            tools = tools,
            capabilityRegistry = capabilities,
        )
        lifecycle.admitToTrial(TOOL_ID)
        recordCleanTrials(lifecycle)

        val firstEvidence = lifecycle.preparePromotionEvidence(TOOL_ID, artifact)
        val secondEvidence = lifecycle.preparePromotionEvidence(TOOL_ID, artifact)

        assertFalse(firstEvidence.activationAllowed)
        assertEquals(firstEvidence.id, secondEvidence.id)
        assertEquals(artifact.id, firstEvidence.candidateArtifactId)
        assertEquals(APK_SHA, firstEvidence.apkSha256)
        assertEquals(GeneratedToolState.TRIAL, tools.get(TOOL_ID)?.state)

        val promoted = lifecycle.promote(TOOL_ID, firstEvidence)

        assertEquals(GeneratedToolState.ACTIVE, promoted.state)
        assertEquals(firstEvidence.id, promoted.promotionEvidenceId)
        val provider = capabilities.providersFor(CAPABILITY_ID).single()
        assertEquals(ProviderType.GENERATED_TOOL, provider.providerType)
        assertEquals(setOf("text"), provider.contract.requiredInputs)
        assertEquals(setOf("normalized-text"), provider.contract.outputs)
        assertEquals(1.0, provider.reliability)
    }

    @Test
    fun `registry cannot bypass evidence gate with direct active transition or registration`() = runTest {
        val tools = GeneratedToolRegistry()
        tools.register(verifiedRecord())
        tools.transition(TOOL_ID, GeneratedToolState.TRIAL)

        assertFailsWith<IllegalArgumentException> {
            tools.transition(TOOL_ID, GeneratedToolState.ACTIVE)
        }
        assertEquals(GeneratedToolState.TRIAL, tools.get(TOOL_ID)?.state)

        assertFailsWith<IllegalArgumentException> {
            tools.register(verifiedRecord(toolId = "active-direct").copy(state = GeneratedToolState.ACTIVE))
        }
    }

    @Test
    fun `candidate apk capability and permission provenance must match tool exactly`() = runTest {
        suspend fun preparedFailure(
            record: GeneratedToolRecord,
            artifact: CandidateArtifact,
        ) {
            val tools = GeneratedToolRegistry()
            tools.register(record)
            val lifecycle = GeneratedToolLifecycleCoordinator(tools)
            lifecycle.admitToTrial(record.manifest.toolId)
            recordCleanTrials(lifecycle, record.manifest.toolId)
            assertFailsWith<IllegalArgumentException> {
                lifecycle.preparePromotionEvidence(record.manifest.toolId, artifact)
            }
            assertEquals(GeneratedToolState.TRIAL, tools.get(record.manifest.toolId)?.state)
        }

        preparedFailure(
            record = verifiedRecord(buildHash = "b".repeat(64)),
            artifact = candidateArtifact(),
        )
        preparedFailure(
            record = verifiedRecord(),
            artifact = candidateArtifact(capabilityId = CapabilityId("text.other")),
        )
        preparedFailure(
            record = verifiedRecord(),
            artifact = candidateArtifact(permissions = setOf(ToolPermission.WRITE_TEMP_FILE)),
        )
    }

    @Test
    fun `reviewer approval and distinct promotion actor are mandatory and ordered`() = runTest {
        suspend fun rejectActors(actors: List<BuildActorEvidence>) {
            val tools = GeneratedToolRegistry()
            tools.register(verifiedRecord())
            val lifecycle = GeneratedToolLifecycleCoordinator(tools)
            lifecycle.admitToTrial(TOOL_ID)
            recordCleanTrials(lifecycle)
            assertFailsWith<IllegalArgumentException> {
                lifecycle.preparePromotionEvidence(TOOL_ID, candidateArtifact(actors = actors))
            }
        }

        rejectActors(
            listOf(
                reviewer(action = BuildActorAction.APPROVED, at = t0.plusSeconds(5)),
            )
        )
        rejectActors(
            listOf(
                reviewer(actorId = "actor:same", action = BuildActorAction.APPROVED, at = t0.plusSeconds(5)),
                promoter(actorId = "actor:same", action = BuildActorAction.PROMOTED, at = t0.plusSeconds(6)),
            )
        )
        rejectActors(
            listOf(
                reviewer(action = BuildActorAction.APPROVED, at = t0.plusSeconds(10)),
                promoter(action = BuildActorAction.PROMOTED, at = t0.plusSeconds(9)),
            )
        )
        rejectActors(
            validActors() + reviewer(
                actorId = "reviewer:rejector",
                action = BuildActorAction.REJECTED,
                at = t0.plusSeconds(7),
            )
        )
        rejectActors(
            validActors() + promoter(
                actorId = "promotion:rollback",
                action = BuildActorAction.ROLLED_BACK,
                at = t0.plusSeconds(7),
            )
        )
    }

    @Test
    fun `promotion evidence becomes stale when another trial is recorded`() = runTest {
        val tools = GeneratedToolRegistry()
        tools.register(verifiedRecord())
        val lifecycle = GeneratedToolLifecycleCoordinator(tools)
        lifecycle.admitToTrial(TOOL_ID)
        recordCleanTrials(lifecycle)
        val evidence = lifecycle.preparePromotionEvidence(TOOL_ID, candidateArtifact())

        lifecycle.recordTrial(
            TOOL_ID,
            GeneratedToolTrialResult(
                invocationId = "trial-late",
                success = true,
                producedExpectedOutput = true,
                latencyMs = 9,
                recordedAt = t0.plusSeconds(99),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            lifecycle.promote(TOOL_ID, evidence)
        }
        assertEquals(GeneratedToolState.TRIAL, tools.get(TOOL_ID)?.state)
    }

    @Test
    fun `promotion policy identity is content sensitive`() {
        val baseline = GeneratedToolPromotionPolicy()
        val stricter = baseline.copy(minimumTrials = 4)
        assertTrue(baseline.fingerprint().isNotBlank())
        assertFalse(baseline.fingerprint() == stricter.fingerprint())
    }

    private suspend fun recordCleanTrials(
        lifecycle: GeneratedToolLifecycleCoordinator,
        toolId: String = TOOL_ID,
    ) {
        repeat(3) { index ->
            lifecycle.recordTrial(
                toolId,
                GeneratedToolTrialResult(
                    invocationId = "trial-$index",
                    success = true,
                    producedExpectedOutput = true,
                    latencyMs = 10L + index,
                    recordedAt = t0.plusSeconds(20L + index),
                ),
            )
        }
    }

    private fun verifiedRecord(
        toolId: String = TOOL_ID,
        buildHash: String = APK_SHA,
        permissions: Set<ToolPermission> = emptySet(),
    ): GeneratedToolRecord = GeneratedToolRecord(
        manifest = GeneratedToolManifest(
            toolId = toolId,
            sourceCapability = CAPABILITY_ID,
            sourceHash = "source-hash-j03",
            buildHash = buildHash,
            permissions = permissions,
            generatedAt = t0,
            requiredInputs = setOf("text"),
            requiredOutputs = setOf("normalized-text"),
        ),
        state = GeneratedToolState.VERIFIED,
        verificationConfidence = 0.95,
    )

    private fun candidateArtifact(
        capabilityId: CapabilityId = CAPABILITY_ID,
        permissions: Set<ToolPermission> = emptySet(),
        actors: List<BuildActorEvidence> = validActors(),
    ): CandidateArtifact {
        val requirement = CapabilityRequirement(
            capabilityId = capabilityId,
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
            summary = "J03 generated tool package",
            implementationNotes = listOf("bounded", "promotion-evidence-bound"),
            plannedSourcePaths = setOf(SOURCE_PATH),
            plannedTestPaths = setOf(TEST_PATH),
        )
        val patch = SourcePatchPlan(
            designSpecId = design.id,
            operations = listOf(
                SourcePatchOperation(SourcePatchOperationType.CREATE, SOURCE_PATH, "class J03Tool"),
                SourcePatchOperation(SourcePatchOperationType.CREATE, TEST_PATH, "class J03ToolTest"),
            ),
        )
        val commands = BuildGateCommand.entries.map { command ->
            BuildCommandResult(
                command = command,
                success = true,
                exitCode = 0,
                outputFingerprint = "output:${command.name}",
            )
        }
        val apk = BuildArtifactEvidence(
            debugApkRef = "artifact://j03-debug.apk",
            debugApkSha256 = APK_SHA,
        )
        val verification = BuildVerificationPolicy().verify(
            BuildVerificationEvidence(
                branchName = BRANCH_NAME,
                branchHeadCommit = APPLIED_HEAD,
                patchPlanId = patch.id,
                commandResults = commands,
                artifact = apk,
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
                    capabilityId = capabilityId,
                    type = BuildCapabilityChangeType.ADDED,
                    requiredInputs = requirement.requiredInputs,
                    outputs = requirement.requiredOutputs,
                )
            ),
            permissionDelta = BuildPermissionDelta(added = permissions),
            actors = actors,
        )
        return CandidateArtifact(candidate, verification, provenance)
    }

    private fun validActors(): List<BuildActorEvidence> = listOf(
        reviewer(action = BuildActorAction.APPROVED, at = t0.plusSeconds(5)),
        promoter(action = BuildActorAction.PROMOTED, at = t0.plusSeconds(6)),
    )

    private fun reviewer(
        actorId: String = "reviewer:local-user",
        action: BuildActorAction,
        at: Instant,
    ) = BuildActorEvidence(
        actorId = actorId,
        role = BuildActorRole.REVIEWER,
        action = action,
        occurredAt = at,
        evidenceRef = "review:$actorId:${action.name}",
    )

    private fun promoter(
        actorId: String = "promotion:local-user",
        action: BuildActorAction,
        at: Instant,
    ) = BuildActorEvidence(
        actorId = actorId,
        role = BuildActorRole.PROMOTION_ACTOR,
        action = action,
        occurredAt = at,
        evidenceRef = "promotion:$actorId:${action.name}",
    )

    companion object {
        private val CAPABILITY_ID = CapabilityId("text.normalize")
        private const val TOOL_ID = "tool-j03"
        private const val SOURCE_COMMIT = "67957af9507d53ff15f033847597f8595c953b0e"
        private const val APPLIED_HEAD = "cccccccccccccccccccccccccccccccccccccccc"
        private const val APK_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val BRANCH_NAME = "buildstudio/candidate-j03"
        private const val SOURCE_PREFIX = "core/runtime/src/main/kotlin/app/lifeos/core/runtime/generated"
        private const val TEST_PREFIX = "core/runtime/src/test/kotlin/app/lifeos/core/runtime/generated"
        private const val SOURCE_PATH = "$SOURCE_PREFIX/J03Tool.kt"
        private const val TEST_PATH = "$TEST_PREFIX/J03ToolTest.kt"
    }
}
