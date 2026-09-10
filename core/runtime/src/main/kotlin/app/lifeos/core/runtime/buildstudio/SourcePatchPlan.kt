package app.lifeos.core.runtime.buildstudio

import app.lifeos.core.field.StableFieldIds

enum class SourcePatchOperationType {
    CREATE,
    UPDATE,
    DELETE,
}

data class SourcePatchOperation(
    val type: SourcePatchOperationType,
    val path: String,
    val content: String? = null,
) {
    init {
        require(path.isSafeRepositoryPath()) { "BuildStudio patch path must be normalized and relative" }
        if (type == SourcePatchOperationType.DELETE) {
            require(content == null) { "Delete operation cannot carry content" }
        } else {
            require(content != null) { "Create/update operation requires content" }
            require(content.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) {
                "BuildStudio patch file exceeds size limit"
            }
        }
    }

    val contentFingerprint: String? = content?.let {
        StableFieldIds.fingerprint("source-patch-content/v1", it)
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "source-patch-operation/v1",
        type.name,
        path,
        contentFingerprint.orEmpty(),
    )

    companion object {
        const val MAX_FILE_BYTES = 512 * 1024
    }
}

data class SourcePatchPlan(
    val designSpecId: String,
    val operations: List<SourcePatchOperation>,
) {
    init {
        require(designSpecId.isNotBlank())
        require(operations.isNotEmpty()) { "BuildStudio patch plan cannot be empty" }
        require(operations.map { it.path }.distinct().size == operations.size) {
            "BuildStudio patch plan may touch a path only once"
        }
        val totalBytes = operations.sumOf { operation ->
            operation.content?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L
        }
        require(totalBytes <= MAX_PLAN_BYTES) { "BuildStudio patch plan exceeds total size limit" }
    }

    val id: String = StableFieldIds.fingerprint(
        "source-patch-plan/v1",
        designSpecId,
        *operations.sortedBy { it.path }.map { it.fingerprint() }.toTypedArray(),
    )

    companion object {
        const val MAX_PLAN_BYTES = 4L * 1024 * 1024
    }
}

fun interface SourcePatchPlanner {
    suspend fun plan(design: BuildDesignSpec): SourcePatchPlan
}

/**
 * Mandatory trust-root barrier for generated BuildStudio candidates. Callers may only add more
 * protected paths; the built-in ownership, recovery, protection, crypto, activation and independent
 * evaluation roots cannot be removed or weakened by configuration.
 */
class BuildPathPolicy(
    additionalProtectedPrefixes: Set<String> = emptySet(),
    additionalProtectedExactPaths: Set<String> = emptySet(),
) {
    private val protectedPrefixes = MANDATORY_PROTECTED_PREFIXES + additionalProtectedPrefixes
    private val protectedExactPaths = MANDATORY_PROTECTED_EXACT_PATHS + additionalProtectedExactPaths

    init {
        require(additionalProtectedPrefixes.none { it.isBlank() })
        require(additionalProtectedExactPaths.none { it.isBlank() })
    }

    fun validate(
        spec: BuildSpec,
        design: BuildDesignSpec,
        patch: SourcePatchPlan,
    ): List<String> {
        val failures = mutableListOf<String>()
        if (design.buildSpecId != spec.id) failures += "design-build-spec-mismatch"
        if (patch.designSpecId != design.id) failures += "patch-design-spec-mismatch"

        val plannedPaths = design.plannedSourcePaths + design.plannedTestPaths
        val operationsByPath = patch.operations.associateBy { it.path }
        val touched = operationsByPath.keys
        (touched - plannedPaths).sorted().forEach { failures += "unplanned-path:$it" }
        (plannedPaths - touched).sorted().forEach { failures += "missing-planned-patch:$it" }
        (spec.requiredTestPaths - design.plannedTestPaths).sorted().forEach { failures += "missing-required-test-plan:$it" }

        val materializedSource = design.plannedSourcePaths.any { path ->
            operationsByPath[path]?.type == SourcePatchOperationType.CREATE ||
                operationsByPath[path]?.type == SourcePatchOperationType.UPDATE
        }
        if (!materializedSource) failures += "missing-materialized-source-patch"

        design.plannedTestPaths.sorted().forEach { path ->
            if (operationsByPath[path]?.type == SourcePatchOperationType.DELETE) {
                failures += "test-delete-forbidden:$path"
            }
        }

        plannedPaths.sorted().forEach { path ->
            if (!path.isSafeRepositoryPath()) failures += "unsafe-planned-path:$path"
            if (!path.isAllowedBy(spec.allowedPathPrefixes)) failures += "path-outside-allowlist:$path"
            if (isProtected(path)) failures += "protected-root-path:$path"
        }
        return failures.distinct().sorted()
    }

    fun isProtected(path: String): Boolean {
        val normalized = path.lowercase()
        return protectedExactPaths.any { normalized == it.lowercase() } ||
            protectedPrefixes.any { normalized.startsWith(it.lowercase()) } ||
            PROTECTED_NAME_TOKENS.any { token -> normalized.substringAfterLast('/').contains(token) }
    }

    private fun String.isAllowedBy(prefixes: Set<String>): Boolean = prefixes.any { rawPrefix ->
        val prefix = rawPrefix.trim().removePrefix("./").trimEnd('/')
        prefix.isNotBlank() && (this == prefix || startsWith("$prefix/"))
    }

    companion object {
        val MANDATORY_PROTECTED_PREFIXES = setOf(
            ".github/",
            ".git/",
            "core/model/src/main/kotlin/app/lifeos/core/model/task/",
            "core/model/src/main/kotlin/app/lifeos/core/model/checkpoint/",
            "core/model/src/main/kotlin/app/lifeos/core/model/health/",
            "core/data/src/main/kotlin/app/lifeos/core/data/task/",
            "core/data/src/main/kotlin/app/lifeos/core/data/checkpoint/",
            "core/data/src/main/kotlin/app/lifeos/core/data/health/",
            "core/data/src/main/kotlin/app/lifeos/core/data/evolution/",
            "core/data/src/main/kotlin/app/lifeos/core/data/capability/",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/boot/",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/tasks/",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/checkpoints/",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/health/",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/recovery/",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/escalation/",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/evolution/",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/deepsearch/",
        )
        val MANDATORY_PROTECTED_EXACT_PATHS = setOf(
            "gradle.properties",
            "settings.gradle.kts",
            "app/src/main/AndroidManifest.xml",
            "app/src/main/res/xml/share_paths.xml",
            "app/src/main/java/app/lifeos/next/kernel/LifeOsKernel.kt",
            "app/src/main/java/app/lifeos/next/kernel/LifeOsKernelFactory.kt",
            "app/src/main/java/app/lifeos/next/kernel/EvolutionRuntimeResources.kt",
            "app/src/main/java/app/lifeos/next/kernel/GoalActionDispatcher.kt",
            "app/src/main/java/app/lifeos/next/kernel/LocalReminderScheduler.kt",
            "app/src/main/java/app/lifeos/next/kernel/LocalScheduleActionExecutor.kt",
            "app/src/main/java/app/lifeos/next/kernel/LocalShareIntentFactory.kt",
            "app/src/main/java/app/lifeos/next/kernel/LocalImageTransformActionExecutor.kt",
            "core/image/src/main/kotlin/app/lifeos/core/image/LocalImageTransformEngine.kt",
            "core/image/src/main/kotlin/app/lifeos/core/image/TransformedImagePhotonFactory.kt",
            "core/language/src/main/kotlin/app/lifeos/core/language/LanguageModels.kt",
            "core/language/src/main/kotlin/app/lifeos/core/language/LanguageUnderstandingEngine.kt",
            "core/language/src/main/kotlin/app/lifeos/core/language/PhotonLanguageContextBuilder.kt",
            "core/language/src/main/kotlin/app/lifeos/core/language/ReferenceResolver.kt",
            "core/language/src/main/kotlin/app/lifeos/core/language/RuleBasedIntentClassifier.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/buildstudio/BuildSpec.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/buildstudio/SourcePatchPlan.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/buildstudio/BuildStudioCoordinator.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/buildstudio/BuildVerification.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/buildstudio/BuildProvenance.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/buildstudio/CandidateArtifact.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/capability/CapabilityRegistry.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/capability/LanguageGoalCapabilityRouter.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/capability/ToolWorkshopCoordinator.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/capability/ToolWorkshopModels.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/capability/GeneratedToolRegistry.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/capability/GeneratedToolSandbox.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/capability/GeneratedToolGenesisCoordinator.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/capability/GeneratedToolRequest.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/capability/GeneratedToolRequestCoordinator.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/capability/GeneratedToolProgram.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/capability/PrivateToolWorkshopAdapters.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/capability/GeneratedToolTrialLifecycle.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/capability/GeneratedToolPromotionEvidence.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/capability/GeneratedToolAudit.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/capability/GeneratedToolPersistentState.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/capability/GeneratedToolStateCodec.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/capability/GeneratedToolBootStateRehydrator.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/goal/GoalResumeEngine.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/goal/LocalKnowledgeGoalEngine.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/goal/LocalDeepSearchGoalEngine.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/goal/LocalScheduleGoalEngine.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/goal/LocalCommunicationGoalEngine.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/goal/LocalImageTransformGoalEngine.kt",
        )
        private val PROTECTED_NAME_TOKENS = setOf(
            "crypto",
            "cipher",
            "encrypted",
            "keystore",
            "signing",
            "vault",
            "cryptoroot",
            "updatetrust",
        )
    }
}

internal fun String.isSafeRepositoryPath(): Boolean {
    if (isBlank() || startsWith('/') || startsWith('\\')) return false
    if (contains('\\')) return false
    val segments = split('/')
    return segments.none { it.isBlank() || it == "." || it == ".." }
}