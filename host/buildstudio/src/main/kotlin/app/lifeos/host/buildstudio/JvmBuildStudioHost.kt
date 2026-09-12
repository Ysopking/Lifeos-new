package app.lifeos.host.buildstudio

import app.lifeos.core.runtime.buildstudio.BuildArtifactCollector
import app.lifeos.core.runtime.buildstudio.BuildArtifactEvidence
import app.lifeos.core.runtime.buildstudio.BuildCommandResult
import app.lifeos.core.runtime.buildstudio.BuildDesignPlanner
import app.lifeos.core.runtime.buildstudio.BuildGateCommand
import app.lifeos.core.runtime.buildstudio.BuildGateRunner
import app.lifeos.core.runtime.buildstudio.BuildSpec
import app.lifeos.core.runtime.buildstudio.BuildStudioCandidate
import app.lifeos.core.runtime.buildstudio.BuildStudioCoordinator
import app.lifeos.core.runtime.buildstudio.BuildStudioExpansionRequest
import app.lifeos.core.runtime.buildstudio.BuildStudioHostAdapter
import app.lifeos.core.runtime.buildstudio.BuildStudioHostState
import app.lifeos.core.runtime.buildstudio.BuildStudioHostStatus
import app.lifeos.core.runtime.buildstudio.BuildStudioResult
import app.lifeos.core.runtime.buildstudio.BuildWorkspaceBranch
import app.lifeos.core.runtime.buildstudio.IsolatedBuildWorkspace
import app.lifeos.core.runtime.buildstudio.PatchApplicationResult
import app.lifeos.core.runtime.buildstudio.SourcePatchOperationType
import app.lifeos.core.runtime.buildstudio.SourcePatchPlan
import app.lifeos.core.runtime.buildstudio.SourcePatchPlanner
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.ArrayDeque

/** Result from one host-side command. The retained output is bounded; its digest covers all output. */
data class HostCommandResult(
    val exitCode: Int,
    val retainedOutput: String,
    val outputSha256: String,
    val diagnostics: List<String>,
) {
    init {
        require(exitCode >= 0)
        require(outputSha256.matches(Regex("[0-9a-f]{64}")))
        require(diagnostics.none { it.isBlank() })
    }
}

fun interface HostProcessExecutor {
    fun run(workingDirectory: Path, command: List<String>): HostCommandResult
}

/** Bounded process execution for the trusted JVM host. No shell expansion is used. */
class BoundedHostProcessExecutor(
    private val maxRetainedChars: Int = 64 * 1024,
    private val maxDiagnosticLines: Int = 40,
) : HostProcessExecutor {
    init {
        require(maxRetainedChars > 0)
        require(maxDiagnosticLines > 0)
    }

    override fun run(workingDirectory: Path, command: List<String>): HostCommandResult {
        require(Files.isDirectory(workingDirectory)) { "Host command working directory does not exist" }
        require(command.isNotEmpty() && command.none { it.isBlank() }) { "Host command must be explicit" }

        val digest = MessageDigest.getInstance("SHA-256")
        val retained = StringBuilder()
        val tail = ArrayDeque<String>()
        val process = ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .redirectErrorStream(true)
            .start()

        process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                val encoded = "$line\n".toByteArray(StandardCharsets.UTF_8)
                digest.update(encoded)
                if (retained.length < maxRetainedChars) {
                    val remaining = maxRetainedChars - retained.length
                    retained.append(line.take(remaining.coerceAtLeast(0)))
                    if (retained.length < maxRetainedChars) retained.append('\n')
                }
                if (line.isNotBlank()) {
                    tail.addLast(line.take(500))
                    while (tail.size > maxDiagnosticLines) tail.removeFirst()
                }
            }
        }
        val exitCode = process.waitFor()
        return HostCommandResult(
            exitCode = exitCode,
            retainedOutput = retained.toString(),
            outputSha256 = digest.digest().toHex(),
            diagnostics = tail.toList(),
        )
    }
}

/** Process-local mapping from deterministic candidate branch to its isolated worktree. */
class BuildStudioWorkspaceIndex {
    private val lock = Any()
    private val paths = linkedMapOf<String, Path>()

    fun register(branchName: String, path: Path) = synchronized(lock) {
        require(branchName.isNotBlank())
        val normalized = path.toAbsolutePath().normalize()
        paths[branchName]?.let { existing ->
            require(existing == normalized) { "Candidate branch is already mapped to another worktree" }
        }
        paths[branchName] = normalized
    }

    fun requirePath(branchName: String): Path = synchronized(lock) {
        requireNotNull(paths[branchName]) { "No isolated worktree registered for $branchName" }
    }
}

/** Concrete git-worktree implementation of the core BuildStudio isolation contract. */
class GitIsolatedBuildWorkspace(
    repositoryRoot: Path,
    workspaceRoot: Path,
    private val index: BuildStudioWorkspaceIndex,
    private val processes: HostProcessExecutor = BoundedHostProcessExecutor(),
    private val gitExecutable: String = "git",
) : IsolatedBuildWorkspace {
    private val repositoryRoot = repositoryRoot.toAbsolutePath().normalize()
    private val workspaceRoot = workspaceRoot.toAbsolutePath().normalize()

    init {
        require(Files.isDirectory(this.repositoryRoot)) { "BuildStudio repository root is missing" }
        Files.createDirectories(this.workspaceRoot)
    }

    override suspend fun createBranch(baseCommit: String, requestedName: String): BuildWorkspaceBranch {
        require(baseCommit.matches(Regex("[0-9a-fA-F]{40}")))
        val verifiedBase = git(repositoryRoot, "rev-parse", "--verify", "$baseCommit^{commit}")
            .retainedOutput.trim().lineSequence().lastOrNull().orEmpty()
        require(verifiedBase.equals(baseCommit, ignoreCase = true)) { "BuildStudio source commit is not exact" }

        val leaf = requestedName.substringAfterLast('/')
        require(leaf.isNotBlank())
        val worktree = workspaceRoot.resolve(leaf).normalize()
        require(worktree.startsWith(workspaceRoot)) { "BuildStudio worktree escaped configured root" }
        require(!Files.exists(worktree)) { "BuildStudio candidate worktree already exists" }

        git(
            repositoryRoot,
            "worktree",
            "add",
            "-b",
            requestedName,
            worktree.toString(),
            baseCommit,
        )
        val head = gitHead(worktree)
        require(head.equals(baseCommit, ignoreCase = true)) { "New candidate branch did not start at source commit" }
        index.register(requestedName, worktree)
        return BuildWorkspaceBranch(requestedName, baseCommit.lowercase(), head.lowercase())
    }

    override suspend fun applyPatch(branch: BuildWorkspaceBranch, plan: SourcePatchPlan): PatchApplicationResult {
        val worktree = index.requirePath(branch.name)
        require(gitHead(worktree).equals(branch.headCommit, ignoreCase = true)) {
            "Candidate branch changed before patch application"
        }

        val touched = sortedSetOf<String>()
        plan.operations.forEach { operation ->
            val target = resolveSafe(worktree, operation.path)
            when (operation.type) {
                SourcePatchOperationType.CREATE -> {
                    require(!Files.exists(target)) { "Create target already exists: ${operation.path}" }
                    Files.createDirectories(requireNotNull(target.parent))
                    Files.writeString(
                        target,
                        requireNotNull(operation.content),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW,
                    )
                }
                SourcePatchOperationType.UPDATE -> {
                    require(Files.isRegularFile(target)) { "Update target is not a file: ${operation.path}" }
                    Files.writeString(
                        target,
                        requireNotNull(operation.content),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.TRUNCATE_EXISTING,
                    )
                }
                SourcePatchOperationType.DELETE -> {
                    require(Files.isRegularFile(target)) { "Delete target is not a file: ${operation.path}" }
                    Files.delete(target)
                }
            }
            touched += operation.path
        }

        git(worktree, "add", "-A", "--", *touched.toTypedArray())
        val staged = processes.run(worktree, listOf(gitExecutable, "diff", "--cached", "--quiet", "--", *touched.toTypedArray()))
        require(staged.exitCode == 1) { "BuildStudio patch produced no staged change" }
        git(
            worktree,
            "-c",
            "user.name=LIFEOS BuildStudio",
            "-c",
            "user.email=buildstudio@local.invalid",
            "commit",
            "--no-gpg-sign",
            "-m",
            "BuildStudio candidate ${plan.id.take(16)}",
        )
        val head = gitHead(worktree)
        require(!head.equals(branch.headCommit, ignoreCase = true)) { "Patch commit did not advance candidate head" }
        val dirty = git(worktree, "status", "--porcelain", "--untracked-files=all").retainedOutput.trim()
        require(dirty.isEmpty()) { "Candidate worktree is dirty after patch commit" }
        return PatchApplicationResult(
            branch = branch.copy(headCommit = head.lowercase()),
            patchPlanId = plan.id,
            appliedPaths = touched,
        )
    }

    private fun resolveSafe(root: Path, repositoryPath: String): Path {
        val resolved = root.resolve(repositoryPath).normalize()
        require(resolved.startsWith(root)) { "Patch path escaped candidate worktree" }
        return resolved
    }

    private fun gitHead(worktree: Path): String =
        git(worktree, "rev-parse", "HEAD").retainedOutput.trim().lineSequence().lastOrNull().orEmpty()

    private fun git(workingDirectory: Path, vararg args: String): HostCommandResult {
        val result = processes.run(workingDirectory, listOf(gitExecutable, *args))
        require(result.exitCode == 0) {
            "Git command failed (${args.firstOrNull().orEmpty()}): ${result.diagnostics.takeLast(5).joinToString(" | ")}"
        }
        return result
    }
}

class GradleBuildGateRunner(
    private val index: BuildStudioWorkspaceIndex,
    private val processes: HostProcessExecutor = BoundedHostProcessExecutor(),
    private val gradleExecutable: String = "gradle",
) : BuildGateRunner {
    override suspend fun run(branch: BuildWorkspaceBranch, command: BuildGateCommand): BuildCommandResult {
        val result = processes.run(
            index.requirePath(branch.name),
            listOf(gradleExecutable, command.command, "--stacktrace", "--no-daemon"),
        )
        return BuildCommandResult(
            command = command,
            success = result.exitCode == 0,
            exitCode = result.exitCode,
            outputFingerprint = result.outputSha256,
            diagnostics = result.diagnostics,
        )
    }
}

class DebugApkArtifactCollector(
    private val index: BuildStudioWorkspaceIndex,
    private val relativeApkPath: String = "app/build/outputs/apk/debug/app-debug.apk",
) : BuildArtifactCollector {
    override suspend fun collectDebugApk(branch: BuildWorkspaceBranch): BuildArtifactEvidence? {
        val root = index.requirePath(branch.name)
        val apk = root.resolve(relativeApkPath).normalize()
        require(apk.startsWith(root)) { "APK path escaped candidate worktree" }
        if (!Files.isRegularFile(apk)) return null
        return BuildArtifactEvidence(
            debugApkRef = "${branch.name}:$relativeApkPath",
            debugApkSha256 = sha256(apk),
        )
    }
}

fun interface BuildStudioExpansionSpecFactory {
    suspend fun bind(request: BuildStudioExpansionRequest): BuildSpec
}

/** Host-side authority for choosing the exact repository commit and patch/test allowlists. */
class RepositoryRefExpansionSpecFactory(
    repositoryRoot: Path,
    private val allowedPathPrefixes: Set<String>,
    private val requiredTestPaths: Set<String>,
    private val sourceRef: String = "main",
    private val processes: HostProcessExecutor = BoundedHostProcessExecutor(),
    private val gitExecutable: String = "git",
) : BuildStudioExpansionSpecFactory {
    private val repositoryRoot = repositoryRoot.toAbsolutePath().normalize()

    init {
        require(Files.isDirectory(this.repositoryRoot))
        require(sourceRef.isNotBlank() && !sourceRef.startsWith('-') && ".." !in sourceRef)
        require(sourceRef.matches(Regex("[A-Za-z0-9._/-]+")))
        require(allowedPathPrefixes.isNotEmpty() && allowedPathPrefixes.none { it.isBlank() })
        require(requiredTestPaths.isNotEmpty() && requiredTestPaths.none { it.isBlank() })
    }

    override suspend fun bind(request: BuildStudioExpansionRequest): BuildSpec {
        require(!request.activationAllowed)
        val result = processes.run(
            repositoryRoot,
            listOf(gitExecutable, "rev-parse", "--verify", "$sourceRef^{commit}"),
        )
        require(result.exitCode == 0) { "Configured BuildStudio source ref cannot be resolved" }
        val sourceCommit = result.retainedOutput.trim().lineSequence().lastOrNull().orEmpty().lowercase()
        require(sourceCommit.matches(Regex("[0-9a-f]{40}"))) { "Resolved BuildStudio source is not a commit SHA" }
        return BuildSpec(
            sourceCommit = sourceCommit,
            gap = request.gap,
            allowedPathPrefixes = allowedPathPrefixes,
            requiredTestPaths = requiredTestPaths,
            genesisHandoff = request.genesisHandoff,
        )
    }
}

data class CandidatePublication(val reference: String) {
    init { require(reference.isNotBlank()) }
}

fun interface BuildStudioCandidatePublisher {
    suspend fun publish(candidate: BuildStudioCandidate): CandidatePublication
}

/** Publishes only an already-verified candidate; GitHub credentials remain in the host environment. */
class GitHubCliCandidatePublisher(
    private val index: BuildStudioWorkspaceIndex,
    private val processes: HostProcessExecutor = BoundedHostProcessExecutor(),
    private val remote: String = "origin",
    private val baseBranch: String = "main",
    private val gitExecutable: String = "git",
    private val ghExecutable: String = "gh",
) : BuildStudioCandidatePublisher {
    init {
        require(remote.isNotBlank() && !remote.startsWith('-'))
        require(baseBranch.isNotBlank() && !baseBranch.startsWith('-'))
    }

    override suspend fun publish(candidate: BuildStudioCandidate): CandidatePublication {
        require(!candidate.activationAllowed)
        val worktree = index.requirePath(candidate.branchName)
        checked(
            worktree,
            listOf(gitExecutable, "push", remote, "HEAD:refs/heads/${candidate.branchName}"),
            "candidate-push",
        )
        val existing = processes.run(
            worktree,
            listOf(ghExecutable, "pr", "view", candidate.branchName, "--json", "url", "--jq", ".url"),
        )
        val reference = if (existing.exitCode == 0 && existing.retainedOutput.trim().isNotBlank()) {
            existing.retainedOutput.trim().lineSequence().last()
        } else {
            checked(
                worktree,
                listOf(
                    ghExecutable,
                    "pr",
                    "create",
                    "--base",
                    baseBranch,
                    "--head",
                    candidate.branchName,
                    "--title",
                    "BuildStudio candidate ${candidate.id.take(12)}",
                    "--body",
                    "Automated non-activating BuildStudio candidate. Verification: ${candidate.verificationId}",
                ),
                "candidate-pr",
            ).retainedOutput.trim().lineSequence().lastOrNull().orEmpty()
        }
        require(reference.isNotBlank()) { "BuildStudio candidate publication returned no PR reference" }
        return CandidatePublication(reference)
    }

    private fun checked(workingDirectory: Path, command: List<String>, stage: String): HostCommandResult {
        val result = processes.run(workingDirectory, command)
        require(result.exitCode == 0) {
            "$stage failed: ${result.diagnostics.takeLast(5).joinToString(" | ")}"
        }
        return result
    }
}

/**
 * Authorized JVM host implementation. It binds Android's credential-free request to a repository
 * commit, runs the core candidate pipeline in an isolated worktree, and publishes only VERIFIED
 * candidates. Git/GitHub credentials never cross the BuildStudioExpansionRequest boundary.
 */
class JvmBuildStudioHost(
    override val id: String,
    repositoryRoot: Path,
    workspaceRoot: Path,
    designer: BuildDesignPlanner,
    patchPlanner: SourcePatchPlanner,
    expansionSpecFactory: BuildStudioExpansionSpecFactory,
    publisherFactory: (BuildStudioWorkspaceIndex) -> BuildStudioCandidatePublisher,
    processes: HostProcessExecutor = BoundedHostProcessExecutor(),
    gitExecutable: String = "git",
    gradleExecutable: String = "gradle",
) : BuildStudioHostAdapter {
    private val repositoryRoot = repositoryRoot.toAbsolutePath().normalize()
    private val index = BuildStudioWorkspaceIndex()
    private val expansionSpecFactory = expansionSpecFactory
    private val publisher = publisherFactory(index)
    private val coordinator = BuildStudioCoordinator(
        designer = designer,
        patchPlanner = patchPlanner,
        workspace = GitIsolatedBuildWorkspace(
            repositoryRoot = this.repositoryRoot,
            workspaceRoot = workspaceRoot,
            index = index,
            processes = processes,
            gitExecutable = gitExecutable,
        ),
        gateRunner = GradleBuildGateRunner(
            index = index,
            processes = processes,
            gradleExecutable = gradleExecutable,
        ),
        artifactCollector = DebugApkArtifactCollector(index),
    )

    init { require(id.isNotBlank()) }

    override suspend fun status(): BuildStudioHostStatus = when {
        !Files.isDirectory(repositoryRoot) -> BuildStudioHostStatus(BuildStudioHostState.STOPPED, "repository-missing")
        else -> BuildStudioHostStatus(BuildStudioHostState.READY, "authorized-jvm-host")
    }

    override suspend fun run(spec: BuildSpec): BuildStudioResult = buildAndPublish(spec)

    override suspend fun expand(request: BuildStudioExpansionRequest): BuildStudioResult {
        require(!request.activationAllowed)
        val spec = try {
            expansionSpecFactory.bind(request)
        } catch (error: Exception) {
            return BuildStudioResult.Failed("host-bind", safeFailure(error))
        }
        if (spec.gap != request.gap || spec.genesisHandoff != request.genesisHandoff) {
            return BuildStudioResult.Failed("host-bind", "bound-spec-does-not-match-request")
        }
        return buildAndPublish(spec)
    }

    private suspend fun buildAndPublish(spec: BuildSpec): BuildStudioResult {
        val result = coordinator.build(spec)
        if (result !is BuildStudioResult.CandidateReady) return result
        return try {
            publisher.publish(result.candidate)
            result
        } catch (error: Exception) {
            BuildStudioResult.Failed("publish", safeFailure(error))
        }
    }

    private fun safeFailure(error: Exception): String =
        "${error::class.simpleName ?: "Exception"}:${error.message.orEmpty().take(180)}"
}

private fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHex()
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
