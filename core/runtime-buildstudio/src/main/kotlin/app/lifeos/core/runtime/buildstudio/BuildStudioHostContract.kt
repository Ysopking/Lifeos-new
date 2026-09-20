package app.lifeos.core.runtime.buildstudio

enum class BuildStudioHostState {
    READY,
    DEGRADED,
    QUARANTINED,
    STOPPED,
}

data class BuildStudioHostStatus(
    val state: BuildStudioHostState,
    val detail: String? = null,
)

/**
 * Trusted host boundary for J01/N BuildStudio execution.
 *
 * The Android/runtime side owns intent/evidence contracts. A host adapter owns repository identity,
 * exact source commit, isolated workspace, build commands and APK collection. Installing a host never
 * grants candidate activation authority; BuildStudioResult remains non-activating and promotion stays
 * in the existing ToolWorkshop/Evolution/Owner-policy path.
 */
interface BuildStudioHostAdapter {
    val id: String
    suspend fun status(): BuildStudioHostStatus
    suspend fun run(spec: BuildSpec): BuildStudioResult

    suspend fun expand(request: BuildStudioExpansionRequest): BuildStudioResult =
        BuildStudioResult.Failed("host", "buildstudio-expansion-not-supported")
}
