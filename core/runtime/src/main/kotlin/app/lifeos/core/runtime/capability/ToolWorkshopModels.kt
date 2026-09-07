package app.lifeos.core.runtime.capability

import java.time.Instant

enum class ToolPermission {
    READ_LOCAL_FILE,
    WRITE_TEMP_FILE,
    WRITE_USER_FILE,
    NETWORK_ACCESS,
    DATABASE_READ,
    DATABASE_WRITE,
    START_WORKER,
    INVOKE_TOOL,
    READ_MEMORY,
    WRITE_MEMORY,
    READ_REPOSITORY,
    MODIFY_REPOSITORY,
}

enum class GeneratedToolState {
    GENERATED,
    BUILT,
    TESTED,
    VERIFIED,
    TRIAL,
    ACTIVE,
    QUARANTINED,
    REJECTED,
    RETIRED,
}

data class ToolSpecification(
    val purpose: String,
    val requiredCapability: CapabilityRequirement,
    val allowedPermissions: Set<ToolPermission> = emptySet(),
    val forbiddenSideEffects: Set<String> = emptySet(),
    val maxSourceBytes: Long = 128_000,
) {
    init {
        require(purpose.isNotBlank()) { "Tool purpose must not be blank" }
        require(maxSourceBytes > 0) { "Maximum source size must be positive" }
        require(forbiddenSideEffects.none { it.isBlank() }) { "Forbidden side effects must not be blank" }
    }
}

data class ToolDesign(
    val toolId: String,
    val specification: ToolSpecification,
    val implementationNotes: String,
) {
    init {
        require(toolId.isNotBlank()) { "Tool id must not be blank" }
        require(implementationNotes.isNotBlank()) { "Implementation notes must not be blank" }
    }
}

data class GeneratedSource(
    val toolId: String,
    val source: String,
) {
    init {
        require(toolId.isNotBlank()) { "Tool id must not be blank" }
        require(source.isNotBlank()) { "Generated source must not be blank" }
    }
}

data class ToolBuildResult(
    val toolId: String,
    val artifactRef: String?,
    val sourceHash: String,
    val buildHash: String?,
    val success: Boolean,
    val diagnostics: List<String> = emptyList(),
)

data class ToolTestResult(
    val success: Boolean,
    val passed: Int,
    val failed: Int,
    val diagnostics: List<String> = emptyList(),
) {
    init {
        require(passed >= 0 && failed >= 0) { "Test counts must not be negative" }
    }
}

data class ToolSecurityResult(
    val accepted: Boolean,
    val violations: List<String> = emptyList(),
)

data class CapabilityVerificationResult(
    val verified: Boolean,
    val confidence: Double,
    val diagnostics: List<String> = emptyList(),
) {
    init {
        require(confidence in 0.0..1.0) { "Verification confidence must be between zero and one" }
    }
}

data class GeneratedToolManifest(
    val toolId: String,
    val sourceCapability: CapabilityId,
    val sourceHash: String,
    val buildHash: String?,
    val permissions: Set<ToolPermission>,
    val generatedAt: Instant,
    val requiredInputs: Set<String> = emptySet(),
    val requiredOutputs: Set<String> = emptySet(),
) {
    init {
        require(toolId.isNotBlank()) { "Tool id must not be blank" }
        require(sourceHash.isNotBlank()) { "Source hash must not be blank" }
        require(requiredInputs.none { it.isBlank() }) { "Generated tool inputs must not be blank" }
        require(requiredOutputs.none { it.isBlank() }) { "Generated tool outputs must not be blank" }
    }
}

data class GeneratedToolRecord(
    val manifest: GeneratedToolManifest,
    val state: GeneratedToolState,
    val verificationConfidence: Double = 0.0,
    val lastMessage: String? = null,
) {
    init {
        require(verificationConfidence in 0.0..1.0) {
            "Tool verification confidence must be between zero and one"
        }
    }
}
