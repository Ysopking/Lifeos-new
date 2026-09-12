package app.lifeos.host.buildstudio

import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.buildstudio.BuildStudioExpansionRequest
import app.lifeos.core.runtime.buildstudio.SourcePatchOperation
import app.lifeos.core.runtime.buildstudio.SourcePatchOperationType
import app.lifeos.core.runtime.buildstudio.SourcePatchPlan
import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.capability.CapabilityGapType
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityRequirement
import app.lifeos.core.runtime.capability.GapSeverity
import app.lifeos.core.runtime.genesis.GenesisHandoff
import app.lifeos.core.runtime.genesis.GenesisHandoffTarget
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class JvmBuildStudioHostTest {
    @Test
    fun gitWorkspaceKeepsBaseImmutableAndCommitsOnlyCandidatePatch() = runTest {
        val repository = createRepository()
        val base = git(repository, "rev-parse", "HEAD")
        val workspaceRoot = Files.createTempDirectory("lifeos-buildstudio-worktrees")
        val index = BuildStudioWorkspaceIndex()
        val workspace = GitIsolatedBuildWorkspace(
            repositoryRoot = repository,
            workspaceRoot = workspaceRoot,
            index = index,
        )

        val branch = workspace.createBranch(base, "buildstudio/candidate-host-contract")
        val patch = SourcePatchPlan(
            designSpecId = "design-host-contract",
            operations = listOf(
                SourcePatchOperation(
                    type = SourcePatchOperationType.UPDATE,
                    path = "feature.txt",
                    content = "candidate\n",
                )
            ),
        )
        val applied = workspace.applyPatch(branch, patch)

        assertEquals(base, branch.baseCommit)
        assertNotEquals(base, applied.branch.headCommit)
        assertEquals(setOf("feature.txt"), applied.appliedPaths)
        assertEquals("candidate\n", Files.readString(index.requirePath(branch.name).resolve("feature.txt")))
        assertEquals("base\n", Files.readString(repository.resolve("feature.txt")))
        assertEquals(base, git(repository, "rev-parse", "HEAD"))
    }

    @Test
    fun expansionFactoryBindsCredentialFreeRequestToExactHostCommit() = runTest {
        val repository = createRepository()
        val base = git(repository, "rev-parse", "HEAD")
        val request = expansionRequest()
        val factory = RepositoryRefExpansionSpecFactory(
            repositoryRoot = repository,
            sourceRef = "main",
            allowedPathPrefixes = setOf("core/runtime/src/main/kotlin"),
            requiredTestPaths = setOf("core/runtime/src/test/kotlin/HostGeneratedTest.kt"),
        )

        val spec = factory.bind(request)

        assertEquals(base, spec.sourceCommit)
        assertEquals(request.gap, spec.gap)
        assertEquals(request.genesisHandoff, spec.genesisHandoff)
        assertTrue(!request.activationAllowed)
    }

    private fun expansionRequest(): BuildStudioExpansionRequest {
        val requirement = CapabilityRequirement(
            capabilityId = CapabilityId("crypto.signing.root"),
            severity = GapSeverity.CRITICAL,
            requiredInputs = setOf("goal-photon"),
            requiredOutputs = setOf("protected-signing-proof"),
        )
        val gap = CapabilityGap(
            requirement = requirement,
            type = CapabilityGapType.CAPABILITY_MISSING,
        )
        val handoff = GenesisHandoff(
            proposalId = "proposal-host-contract",
            target = GenesisHandoffTarget.BUILD_STUDIO,
            referenceId = "module-proposal-host-contract",
            payloadFingerprint = "payload-host-contract",
            requiresExplicitApproval = true,
        )
        return BuildStudioExpansionRequest(
            gap = gap,
            genesisHandoff = handoff,
            sourcePhotonId = PhotonId("source-host-contract"),
            sourcePhotonRevision = 1L,
            goalPhotonId = PhotonId("goal-host-contract"),
            goalPhotonRevision = 1L,
            gapPhotonId = PhotonId("gap-host-contract"),
        )
    }

    private fun createRepository(): Path {
        val repository = Files.createTempDirectory("lifeos-buildstudio-repo")
        git(repository, "init")
        git(repository, "config", "user.name", "LIFEOS Test")
        git(repository, "config", "user.email", "lifeos-test@local.invalid")
        Files.writeString(repository.resolve("feature.txt"), "base\n")
        git(repository, "add", "feature.txt")
        git(repository, "commit", "-m", "base")
        git(repository, "branch", "-M", "main")
        return repository
    }

    private fun git(repository: Path, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", *args))
            .directory(repository.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        check(exitCode == 0) { "git ${args.joinToString(" ")} failed: $output" }
        return output.trim().lineSequence().lastOrNull().orEmpty()
    }
}
