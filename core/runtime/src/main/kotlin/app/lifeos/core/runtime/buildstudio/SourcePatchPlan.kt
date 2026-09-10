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
    val contentFingerprint: String? = null,
) {
    init {
        require(path.isSafeRepositoryPath()) { "BuildStudio patch path must be normalized and relative" }
        if (type == SourcePatchOperationType.DELETE) {
            require(contentFingerprint == null) { "Delete operation cannot carry content fingerprint" }
        } else {
            require(!contentFingerprint.isNullOrBlank()) { "Create/update operation requires content fingerprint" }
        }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "source-patch-operation/v1",
        type.name,
        path,
        contentFingerprint.orEmpty(),
    )
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
    }

    val id: String = StableFieldIds.fingerprint(
        "source-patch-plan/v1",
        designSpecId,
        *operations.sortedBy { it.path }.map { it.fingerprint() }.toTypedArray(),
    )
}

fun interface SourcePatchPlanner {
    suspend fun plan(design: BuildDesignSpec): SourcePatchPlan
}

class BuildPathPolicy(
    private val protectedPrefixes: Set<String> = DEFAULT_PROTECTED_PREFIXES,
    private val protectedExactPaths: Set<String> = DEFAULT_PROTECTED_EXACT_PATHS,
) {
    fun validate(
        spec: BuildSpec,
        design: BuildDesignSpec,
        patch: SourcePatchPlan,
    ): List<String> {
        val failures = mutableListOf<String>()
        if (design.buildSpecId != spec.id) failures += "design-build-spec-mismatch"
        if (patch.designSpecId != design.id) failures += "patch-design-spec-mismatch"

        val plannedPaths = design.plannedSourcePaths + design.plannedTestPaths
        val touched = patch.operations.mapTo(linkedSetOf()) { it.path }
        (touched - plannedPaths).sorted().forEach { failures += "unplanned-path:$it" }
        (design.plannedTestPaths - touched).sorted().forEach { failures += "missing-planned-test-patch:$it" }
        (spec.requiredTestPaths - design.plannedTestPaths).sorted().forEach { failures += "missing-required-test-plan:$it" }

        touched.sorted().forEach { path ->
            if (!path.isAllowedBy(spec.allowedPathPrefixes)) failures += "path-outside-allowlist:$path"
            if (isProtected(path)) failures += "protected-root-path:$path"
        }
        return failures.distinct().sorted()
    }

    fun isProtected(path: String): Boolean {
        val normalized = path.lowercase()
        return protectedExactPaths.any { normalized == it.lowercase() } ||
            protectedPrefixes.any { normalized.startsWith(it.lowercase()) } ||
            PROTECTED_NAME_TOKENS.any { token ->
                normalized.substringAfterLast('/').contains(token)
            }
    }

    private fun String.isAllowedBy(prefixes: Set<String>): Boolean = prefixes.any { rawPrefix ->
        val prefix = rawPrefix.trim().removePrefix("./").trimEnd('/')
        this == prefix || startsWith("$prefix/")
    }

    companion object {
        val DEFAULT_PROTECTED_PREFIXES = setOf(
            ".github/",
            ".git/",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/tasks/",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/health/",
            "core/data/src/main/kotlin/app/lifeos/core/data/health/",
        )
        val DEFAULT_PROTECTED_EXACT_PATHS = setOf(
            "gradle.properties",
            "settings.gradle.kts",
            "app/src/main/AndroidManifest.xml",
        )
        private val PROTECTED_NAME_TOKENS = setOf(
            "keystore",
            "signing",
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
