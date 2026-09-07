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

data class GeneratedToolSandboxPermit internal constructor(
    val toolId: String,
    val invocationId: String,
    val profileId: String,
    val grantedPermissions: Set<ToolPermission>,
)

sealed interface GeneratedToolInvocationDecision {
    data class Granted(val permit: GeneratedToolSandboxPermit) : GeneratedToolInvocationDecision

    data class Denied(
        val toolId: String,
        val invocationId: String,
        val reasons: List<String>,
    ) : GeneratedToolInvocationDecision {
        init {
            require(reasons.isNotEmpty()) { "Invocation denial requires at least one reason" }
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

        val reasons = baseReasons(record)
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

    /**
     * Per-invocation execution gate. Future generated-tool runners must require a
     * permit from this method before touching any permission-bearing capability.
     */
    fun authorizeTrialInvocation(
        record: GeneratedToolRecord,
        invocationId: String,
        requestedPermissions: Set<ToolPermission>,
    ): GeneratedToolInvocationDecision {
        require(invocationId.isNotBlank()) { "Invocation id must not be blank" }
        require(record.state == GeneratedToolState.TRIAL) {
            "Sandbox invocation permits are issued only for TRIAL tools"
        }

        val reasons = buildList {
            addAll(baseReasons(record))
            val undeclared = requestedPermissions - record.manifest.permissions
            if (undeclared.isNotEmpty()) {
                add(
                    "permissions-not-declared:" +
                        undeclared.sortedBy { it.name }.joinToString(",") { it.name }
                )
            }
            val outsideProfile = requestedPermissions - profile.allowedPermissions
            if (outsideProfile.isNotEmpty()) {
                add(
                    "permissions-outside-sandbox:" +
                        outsideProfile.sortedBy { it.name }.joinToString(",") { it.name }
                )
            }
        }

        return if (reasons.isEmpty()) {
            GeneratedToolInvocationDecision.Granted(
                GeneratedToolSandboxPermit(
                    toolId = record.manifest.toolId,
                    invocationId = invocationId,
                    profileId = profile.profileId,
                    grantedPermissions = requestedPermissions,
                )
            )
        } else {
            GeneratedToolInvocationDecision.Denied(
                toolId = record.manifest.toolId,
                invocationId = invocationId,
                reasons = reasons.distinct(),
            )
        }
    }

    private fun baseReasons(record: GeneratedToolRecord): List<String> = buildList {
        val disallowed = record.manifest.permissions - profile.allowedPermissions
        if (disallowed.isNotEmpty()) {
            add("permissions-not-allowed:${disallowed.sortedBy { it.name }.joinToString(",") { it.name }}")
        }
        if (profile.requireBuildHash && record.manifest.buildHash.isNullOrBlank()) {
            add("missing-build-hash")
        }
        if (record.verificationConfidence < profile.minimumVerificationConfidence) {
            add(
                "verification-confidence-below-threshold:" +
                    "${record.verificationConfidence}<${profile.minimumVerificationConfidence}"
            )
        }
    }
}
