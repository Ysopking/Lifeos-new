package app.lifeos.core.runtime.buildstudio

import app.lifeos.core.field.StableFieldIds
import kotlinx.coroutines.CancellationException

data class BuildWorkspaceBranch(
    val name: String,
    val baseCommit: String,
    val headCommit: String,
) {
    init {
        require(name.isSafeBuildBranchName()) { "BuildStudio branch must be isolated from protected branches" }
        require(baseCommit.matches(Regex("[0-9a-fA-F]{40}")))
        require(headCommit.matches(Regex("[0-9a-fA-F]{40}")))
    }
}

data class PatchApplicationResult(
    val branch: BuildWorkspaceBranch,
    val patchPlanId: String,
    val appliedPaths: Set<String>,
) {
    init {
        require(patchPlanId.isNotBlank())
        require(appliedPaths.isNotEmpty())
        require(appliedPaths.none { it.isBlank() })
    }
}

interface IsolatedBuildWorkspace {
    suspend fun createBranch(baseCommit: String, requestedName: String): BuildWorkspaceBranch
    suspend fun applyPatch(branch: BuildWorkspaceBranch, plan: SourcePatchPlan): PatchApplicationResult
}

fun interface BuildGateRunner {
    suspend fun run(branch: BuildWorkspaceBranch, command: BuildGateCommand): BuildCommandResult
}

fun interface BuildArtifactCollector {
    suspend fun collectDebugApk(branch: BuildWorkspaceBranch): BuildArtifactEvidence?
}

data class BuildStudioCandidate(
    val id: String,
    val buildSpecId: String,
    val designSpecId: String,
    val patchPlanId: String,
    val branchName: String,
    val branchHeadCommit: String,
    val verificationId: String,
) {
    init {
        require(id.isNotBlank())
        require(buildSpecId.isNotBlank() && designSpecId.isNotBlank() && patchPlanId.isNotBlank())
        require(branchName.isSafeBuildBranchName())
        require(branchHeadCommit.matches(Regex("[0-9a-fA-F]{40}")))
        require(verificationId.isNotBlank())
    }

    /** J01 creates a candidate only. Promotion/activation belongs to later gated blocks. */
    val activationAllowed: Boolean = false
}

sealed interface BuildStudioResult {
    data class CandidateReady(
        val candidate: BuildStudioCandidate,
        val verification: BuildVerification,
    ) : BuildStudioResult {
        init {
            require(verification.status == BuildVerificationStatus.VERIFIED)
            require(!candidate.activationAllowed)
        }
    }

    data class Rejected(
        val stage: String,
        val failures: List<String>,
        val verification: BuildVerification? = null,
    ) : BuildStudioResult {
        init {
            require(stage.isNotBlank())
            require(failures.isNotEmpty() && failures.none { it.isBlank() })
        }
    }

    data class Failed(
        val stage: String,
        val reason: String,
    ) : BuildStudioResult {
        init { require(stage.isNotBlank() && reason.isNotBlank()) }
    }
}

/**
 * J01 candidate pipeline. The coordinator owns ordering and validation but has no GitHub, filesystem
 * or shell implementation. Every mutation is mediated by an isolated workspace created from the
 * exact source commit, and successful output is non-activating candidate evidence only.
 */
class BuildStudioCoordinator(
    private val designer: BuildDesignPlanner,
    private val patchPlanner: SourcePatchPlanner,
    private val workspace: IsolatedBuildWorkspace,
    private val gateRunner: BuildGateRunner,
    private val artifactCollector: BuildArtifactCollector,
    private val pathPolicy: BuildPathPolicy = BuildPathPolicy(),
    private val verificationPolicy: BuildVerificationPolicy = BuildVerificationPolicy(),
) {
    suspend fun build(spec: BuildSpec): BuildStudioResult {
        return try {
            val design = designer.design(spec)
            if (design.buildSpecId != spec.id || design.capability != spec.gap.requirement) {
                return BuildStudioResult.Rejected("design", listOf("design-does-not-match-build-spec"))
            }

            val patch = patchPlanner.plan(design)
            val pathFailures = pathPolicy.validate(spec, design, patch)
            if (pathFailures.isNotEmpty()) {
                return BuildStudioResult.Rejected("patch-policy", pathFailures)
            }

            val requestedBranch = deterministicBranchName(spec, design, patch)
            val isolated = workspace.createBranch(spec.sourceCommit, requestedBranch)
            validateCreatedBranch(spec, requestedBranch, isolated)?.let { failure ->
                return BuildStudioResult.Rejected("branch-isolation", listOf(failure))
            }

            val applied = workspace.applyPatch(isolated, patch)
            validateAppliedPatch(isolated, patch, applied)?.let { failures ->
                return BuildStudioResult.Rejected("patch-application", failures)
            }

            val results = BUILD_GATE_ORDER.map { command ->
                gateRunner.run(applied.branch, command).also { result ->
                    require(result.command == command) { "Build gate runner changed requested command" }
                }
            }
            val allCommandsSuccessful = results.all { it.success }
            val artifact = if (allCommandsSuccessful) {
                artifactCollector.collectDebugApk(applied.branch)
            } else {
                null
            }
            val evidence = BuildVerificationEvidence(
                branchName = applied.branch.name,
                branchHeadCommit = applied.branch.headCommit,
                patchPlanId = patch.id,
                commandResults = results,
                artifact = artifact,
            )
            val verification = verificationPolicy.verify(evidence)
            if (verification.status != BuildVerificationStatus.VERIFIED) {
                return BuildStudioResult.Rejected(
                    stage = "verification",
                    failures = verification.failures,
                    verification = verification,
                )
            }

            val candidateId = StableFieldIds.fingerprint(
                "buildstudio-candidate/v1",
                spec.id,
                design.id,
                patch.id,
                applied.branch.name,
                applied.branch.headCommit,
                verification.id,
            )
            BuildStudioResult.CandidateReady(
                candidate = BuildStudioCandidate(
                    id = candidateId,
                    buildSpecId = spec.id,
                    designSpecId = design.id,
                    patchPlanId = patch.id,
                    branchName = applied.branch.name,
                    branchHeadCommit = applied.branch.headCommit,
                    verificationId = verification.id,
                ),
                verification = verification,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            BuildStudioResult.Failed(
                stage = "pipeline",
                reason = "${error::class.simpleName ?: "Exception"}:${error.message.orEmpty().take(180)}",
            )
        }
    }

    private fun deterministicBranchName(
        spec: BuildSpec,
        design: BuildDesignSpec,
        patch: SourcePatchPlan,
    ): String = "buildstudio/candidate-${StableFieldIds.fingerprint(
        "buildstudio-branch/v1",
        spec.sourceCommit,
        spec.id,
        design.id,
        patch.id,
    ).take(20)}"

    private fun validateCreatedBranch(
        spec: BuildSpec,
        requestedName: String,
        branch: BuildWorkspaceBranch,
    ): String? = when {
        branch.name != requestedName -> "workspace-changed-branch-name"
        branch.baseCommit.lowercase() != spec.sourceCommit.lowercase() -> "workspace-changed-base-commit"
        branch.headCommit.lowercase() != spec.sourceCommit.lowercase() -> "new-branch-head-does-not-match-source-commit"
        else -> null
    }

    private fun validateAppliedPatch(
        initial: BuildWorkspaceBranch,
        patch: SourcePatchPlan,
        applied: PatchApplicationResult,
    ): List<String>? {
        val failures = mutableListOf<String>()
        if (applied.branch.name != initial.name) failures += "patch-moved-to-different-branch"
        if (applied.branch.baseCommit.lowercase() != initial.baseCommit.lowercase()) {
            failures += "patch-changed-base-commit"
        }
        if (applied.patchPlanId != patch.id) failures += "workspace-changed-patch-plan-id"
        val expectedPaths = patch.operations.mapTo(sortedSetOf()) { it.path }
        if (applied.appliedPaths != expectedPaths) failures += "workspace-applied-path-set-mismatch"
        if (applied.branch.headCommit.lowercase() == initial.headCommit.lowercase()) {
            failures += "patch-did-not-create-new-head"
        }
        return failures.distinct().sorted().takeIf { it.isNotEmpty() }
    }

    private companion object {
        val BUILD_GATE_ORDER = listOf(
            BuildGateCommand.TEST,
            BuildGateCommand.LINT_DEBUG,
            BuildGateCommand.ASSEMBLE_DEBUG,
        )
    }
}

private fun String.isSafeBuildBranchName(): Boolean {
    val normalized = trim().lowercase()
    return normalized.startsWith("buildstudio/") &&
        normalized != "buildstudio/main" &&
        normalized != "buildstudio/master" &&
        !normalized.contains("..") &&
        !normalized.contains(' ') &&
        !normalized.contains('\\')
}
