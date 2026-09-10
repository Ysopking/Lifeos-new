package app.lifeos.core.runtime.buildstudio

import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.capability.CapabilityGapType
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityRequirement
import app.lifeos.core.runtime.capability.GapSeverity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BuildStudioCoordinatorTest {
    @Test
    fun `successful pipeline isolates branch before patch and returns non activating candidate`() = runBlocking {
        val fixture = Fixture()
        val result = fixture.coordinator().build(fixture.spec)

        val ready = assertIs<BuildStudioResult.CandidateReady>(result)
        assertFalse(ready.candidate.activationAllowed)
        assertEquals(BuildVerificationStatus.VERIFIED, ready.verification.status)
        assertEquals(
            listOf("branch", "patch", "gate:TEST", "gate:LINT_DEBUG", "gate:ASSEMBLE_DEBUG", "artifact"),
            fixture.events,
        )
        assertTrue(ready.candidate.branchName.startsWith("buildstudio/candidate-"))
        assertEquals(APPLIED_HEAD, ready.candidate.branchHeadCommit)
        assertEquals(ready.verification.id, ready.candidate.verificationId)
        assertEquals(ready.verification.evidence.patchPlanId, ready.candidate.patchPlanId)
    }

    @Test
    fun `failed lint rejects candidate and never collects apk`() = runBlocking {
        val fixture = Fixture(failingCommand = BuildGateCommand.LINT_DEBUG)
        val result = fixture.coordinator().build(fixture.spec)

        val rejected = assertIs<BuildStudioResult.Rejected>(result)
        assertEquals("verification", rejected.stage)
        assertTrue("failed-command:LINT_DEBUG" in rejected.failures)
        assertTrue("missing-debug-apk-evidence" in rejected.failures)
        assertFalse("artifact" in fixture.events)
    }

    @Test
    fun `missing apk evidence rejects otherwise green build`() = runBlocking {
        val fixture = Fixture(artifact = null)
        val result = fixture.coordinator().build(fixture.spec)

        val rejected = assertIs<BuildStudioResult.Rejected>(result)
        assertEquals(listOf("missing-debug-apk-evidence"), rejected.failures)
    }

    @Test
    fun `workspace cannot move patch to a different candidate branch`() = runBlocking {
        val fixture = Fixture(movePatchBranch = true)
        val result = fixture.coordinator().build(fixture.spec)

        val rejected = assertIs<BuildStudioResult.Rejected>(result)
        assertEquals("patch-application", rejected.stage)
        assertTrue("patch-moved-to-different-branch" in rejected.failures)
        assertTrue(fixture.events.none { it.startsWith("gate:") })
    }

    @Test
    fun `same immutable inputs produce same branch verification and candidate ids`() = runBlocking {
        val left = Fixture()
        val right = Fixture()

        val first = assertIs<BuildStudioResult.CandidateReady>(left.coordinator().build(left.spec))
        val second = assertIs<BuildStudioResult.CandidateReady>(right.coordinator().build(right.spec))

        assertEquals(first.candidate.id, second.candidate.id)
        assertEquals(first.candidate.branchName, second.candidate.branchName)
        assertEquals(first.verification.id, second.verification.id)
    }

    @Test
    fun `cancellation from build gate propagates`() = runBlocking {
        val fixture = Fixture(cancelOnCommand = BuildGateCommand.TEST)

        assertFailsWith<CancellationException> {
            fixture.coordinator().build(fixture.spec)
        }
    }

    @Test
    fun `main cannot be represented as BuildStudio workspace branch`() {
        assertFailsWith<IllegalArgumentException> {
            BuildWorkspaceBranch("main", SOURCE_COMMIT, SOURCE_COMMIT)
        }
    }

    private class Fixture(
        private val failingCommand: BuildGateCommand? = null,
        private val cancelOnCommand: BuildGateCommand? = null,
        private val artifact: BuildArtifactEvidence? = BuildArtifactEvidence(
            debugApkRef = "artifact://debug.apk",
            debugApkSha256 = "a".repeat(64),
        ),
        private val movePatchBranch: Boolean = false,
    ) {
        val events = mutableListOf<String>()
        val requirement = CapabilityRequirement(
            capabilityId = CapabilityId("module.example.generate"),
            severity = GapSeverity.BLOCKING,
            requiredInputs = setOf("goal-photon"),
            requiredOutputs = setOf("example-output"),
        )
        val spec = BuildSpec(
            sourceCommit = SOURCE_COMMIT,
            gap = CapabilityGap(requirement, CapabilityGapType.CAPABILITY_MISSING),
            allowedPathPrefixes = setOf(
                "core/runtime/src/main/kotlin/app/lifeos/core/runtime/buildstudio/generated",
                "core/runtime/src/test/kotlin/app/lifeos/core/runtime/buildstudio/generated",
            ),
            requiredTestPaths = setOf(TEST_PATH),
        )

        fun coordinator(): BuildStudioCoordinator {
            val designer = BuildDesignPlanner { buildSpec ->
                BuildDesignSpec(
                    buildSpecId = buildSpec.id,
                    capability = buildSpec.gap.requirement,
                    summary = "generate example module",
                    implementationNotes = listOf("bounded implementation"),
                    plannedSourcePaths = setOf(SOURCE_PATH),
                    plannedTestPaths = setOf(TEST_PATH),
                )
            }
            val patchPlanner = SourcePatchPlanner { design ->
                SourcePatchPlan(
                    designSpecId = design.id,
                    operations = listOf(
                        SourcePatchOperation(SourcePatchOperationType.CREATE, SOURCE_PATH, "class GeneratedExample"),
                        SourcePatchOperation(SourcePatchOperationType.CREATE, TEST_PATH, "class GeneratedExampleTest"),
                    ),
                )
            }
            val workspace = object : IsolatedBuildWorkspace {
                override suspend fun createBranch(baseCommit: String, requestedName: String): BuildWorkspaceBranch {
                    events += "branch"
                    return BuildWorkspaceBranch(requestedName, baseCommit, baseCommit)
                }

                override suspend fun applyPatch(
                    branch: BuildWorkspaceBranch,
                    plan: SourcePatchPlan,
                ): PatchApplicationResult {
                    events += "patch"
                    val name = if (movePatchBranch) "buildstudio/candidate-other" else branch.name
                    return PatchApplicationResult(
                        branch = BuildWorkspaceBranch(name, branch.baseCommit, APPLIED_HEAD),
                        patchPlanId = plan.id,
                        appliedPaths = plan.operations.mapTo(sortedSetOf()) { it.path },
                    )
                }
            }
            val runner = BuildGateRunner { _, command ->
                events += "gate:${command.name}"
                if (command == cancelOnCommand) throw CancellationException("cancelled")
                val success = command != failingCommand
                BuildCommandResult(
                    command = command,
                    success = success,
                    exitCode = if (success) 0 else 1,
                    outputFingerprint = "output-${command.name}",
                    diagnostics = if (success) emptyList() else listOf("failed-${command.name}"),
                )
            }
            val collector = BuildArtifactCollector {
                events += "artifact"
                artifact
            }
            return BuildStudioCoordinator(designer, patchPlanner, workspace, runner, collector)
        }
    }

    companion object {
        private const val SOURCE_COMMIT = "6780ed5fa0e32d52239710fa3762daa8330872b2"
        private const val APPLIED_HEAD = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        private const val SOURCE_PATH = "core/runtime/src/main/kotlin/app/lifeos/core/runtime/buildstudio/generated/GeneratedExample.kt"
        private const val TEST_PATH = "core/runtime/src/test/kotlin/app/lifeos/core/runtime/buildstudio/generated/GeneratedExampleTest.kt"
    }
}
