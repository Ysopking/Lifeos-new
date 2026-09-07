package app.lifeos.core.runtime.capability

/**
 * Policy admission for generated tools. This is intentionally separate from the
 * future execution runner: passing admission does not grant process execution,
 * Android permissions, network access or repository mutation by itself.
 */
data class GeneratedToolSandboxProfile(
    val profileId: String = "strict-local-trial-v1",
    val allowedPermissions: Set<ToolPermission> = setOf(ToolPermission.WRITE_TEMP_FILE),
    val minimumVerificationConfidence: Double = 0.85,
    val requireBuildHash: Boolean = true,
) {
    init {
        require(profileId.isNotBlank()) { "Sandbox profile id must not be blank" }
        require(minimumVerificationConfidence in 0.0..1.0) {
            "Sandbox verification confidence must be normalized"
        }
    }
}

sealed interface GeneratedToolSandboxDecision {
    data class Admitted(
        val toolId: String,
        val profileId: String,
        val effectivePermissions: Set<ToolPermission>,
    ) : GeneratedToolSandboxDecision

    data class Denied(
        val toolId: String,
        val reasons: List<String>,
    ) : GeneratedToolSandboxDecision {
        init {
            require(reasons.isNotEmpty()) { "Sandbox denial requires at least one reason" }
        }
    }
}

class GeneratedToolSandboxAdmission(
    private val profile: GeneratedToolSandboxProfile = GeneratedToolSandboxProfile(),
) {
    fun evaluate(record: GeneratedToolRecord): GeneratedToolSandboxDecision {
        require(record.state == GeneratedToolState.VERIFIED) {
            "Sandbox admission accepts only VERIFIED generated tools"
        }

        val reasons = buildList {
            val disallowed = record.manifest.permissions - profile.allowedPermissions
            if (disallowed.isNotEmpty()) {
                add("permissions-not-allowed:${disallowed.sortedBy { it.name }.joinToString(",") { it.name }}")
            }
            if (
                profile.requireBuildHash &&
                record.manifest.buildHash.isNullOrBlank()
            ) {
                add("missing-build-hash")
            }
            if (record.verificationConfidence < profile.minimumVerificationConfidence) {
                add(
                    "verification-confidence-below-threshold:" +
                        "${record.verificationConfidence}<${profile.minimumVerificationConfidence}"
                )
            }
        }

        return if (reasons.isEmpty()) {
            GeneratedToolSandboxDecision.Admitted(
                toolId = record.manifest.toolId,
                profileId = profile.profileId,
                effectivePermissions = record.manifest.permissions,
            )
        } else {
            GeneratedToolSandboxDecision.Denied(
                toolId = record.manifest.toolId,
                reasons = reasons,
            )
        }
    }
}
