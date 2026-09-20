package app.lifeos.core.runtime.buildstudio

import app.lifeos.core.field.StableFieldIds

enum class BuildGateCommand(val command: String) {
    TEST("test"),
    LINT_DEBUG(":app:lintDebug"),
    ASSEMBLE_DEBUG(":app:assembleDebug"),
}

data class BuildCommandResult(
    val command: BuildGateCommand,
    val success: Boolean,
    val exitCode: Int,
    val outputFingerprint: String,
    val diagnostics: List<String> = emptyList(),
) {
    init {
        require(exitCode >= 0) { "Build command exit code must not be negative" }
        require(outputFingerprint.isNotBlank()) { "Build command requires output fingerprint" }
        require(diagnostics.none { it.isBlank() })
        require(success == (exitCode == 0)) { "Build command success must match exit code" }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "build-command-result/v1",
        command.name,
        success.toString(),
        exitCode.toString(),
        outputFingerprint,
        *diagnostics.sorted().map { "diagnostic:$it" }.toTypedArray(),
    )
}

data class BuildArtifactEvidence(
    val debugApkRef: String,
    val debugApkSha256: String,
) {
    init {
        require(debugApkRef.isNotBlank()) { "Verified build requires debug APK reference" }
        require(debugApkSha256.matches(Regex("[0-9a-f]{64}"))) {
            "Verified build requires lowercase SHA-256 APK digest"
        }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "build-artifact-evidence/v1",
        debugApkRef,
        debugApkSha256,
    )
}

data class BuildVerificationEvidence(
    val branchName: String,
    val branchHeadCommit: String,
    val patchPlanId: String,
    val commandResults: List<BuildCommandResult>,
    val artifact: BuildArtifactEvidence?,
) {
    init {
        require(branchName.isSafeBuildStudioBranchName()) {
            "Build verification requires an isolated BuildStudio candidate branch"
        }
        require(branchHeadCommit.matches(Regex("[0-9a-fA-F]{40}")))
        require(patchPlanId.isNotBlank())
        require(commandResults.map { it.command }.distinct().size == commandResults.size) {
            "Build verification cannot contain duplicate gate commands"
        }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "build-verification-evidence/v1",
        branchName,
        branchHeadCommit.lowercase(),
        patchPlanId,
        artifact?.fingerprint().orEmpty(),
        *commandResults.sortedBy { it.command.name }.map { it.fingerprint() }.toTypedArray(),
    )
}

enum class BuildVerificationStatus {
    VERIFIED,
    REJECTED,
}

data class BuildVerification(
    val status: BuildVerificationStatus,
    val evidence: BuildVerificationEvidence,
    val failures: List<String>,
) {
    init {
        require(failures.none { it.isBlank() })
        if (status == BuildVerificationStatus.VERIFIED) {
            require(failures.isEmpty())
            require(evidence.artifact != null)
        } else {
            require(failures.isNotEmpty())
        }
    }

    val id: String = StableFieldIds.fingerprint(
        "build-verification/v1",
        status.name,
        evidence.fingerprint(),
        *failures.sorted().toTypedArray(),
    )
}

/** Mandatory J01 gate. Tests, lintDebug and assembleDebug cannot be configured away. */
class BuildVerificationPolicy {
    fun verify(evidence: BuildVerificationEvidence): BuildVerification {
        val failures = mutableListOf<String>()
        val byCommand = evidence.commandResults.associateBy { it.command }
        MANDATORY_COMMANDS.sortedBy { it.name }.forEach { command ->
            val result = byCommand[command]
            when {
                result == null -> failures += "missing-command:${command.name}"
                !result.success -> failures += "failed-command:${command.name}"
            }
        }
        val unexpected = byCommand.keys - MANDATORY_COMMANDS
        unexpected.sortedBy { it.name }.forEach { command -> failures += "unexpected-command:${command.name}" }
        if (evidence.artifact == null) failures += "missing-debug-apk-evidence"

        val stable = failures.distinct().sorted()
        return BuildVerification(
            status = if (stable.isEmpty()) BuildVerificationStatus.VERIFIED else BuildVerificationStatus.REJECTED,
            evidence = evidence,
            failures = stable,
        )
    }

    private companion object {
        val MANDATORY_COMMANDS = setOf(
            BuildGateCommand.TEST,
            BuildGateCommand.LINT_DEBUG,
            BuildGateCommand.ASSEMBLE_DEBUG,
        )
    }
}

internal fun String.isSafeBuildStudioBranchName(): Boolean {
    val normalized = lowercase()
    return isNotBlank() &&
        this == trim() &&
        normalized.startsWith("buildstudio/candidate-") &&
        normalized != "buildstudio/candidate-main" &&
        normalized != "buildstudio/candidate-master" &&
        !normalized.contains("..") &&
        !normalized.contains(' ') &&
        !normalized.contains('\\')
}
