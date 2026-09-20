package app.lifeos.core.runtime.buildstudio

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.capability.CapabilityRequirement
import app.lifeos.core.runtime.genesis.GenesisHandoff
import app.lifeos.core.runtime.genesis.GenesisHandoffTarget

data class BuildSpec(
    val sourceCommit: String,
    val gap: CapabilityGap,
    val allowedPathPrefixes: Set<String>,
    val requiredTestPaths: Set<String>,
    val genesisHandoff: GenesisHandoff? = null,
) {
    init {
        require(sourceCommit.isCommitSha()) { "BuildStudio source commit must be a 40-character git SHA" }
        require(allowedPathPrefixes.isNotEmpty()) { "BuildStudio requires at least one allowed path prefix" }
        require(allowedPathPrefixes.none { it.isBlank() })
        require(requiredTestPaths.isNotEmpty()) { "BuildStudio requires explicit test paths" }
        require(requiredTestPaths.none { it.isBlank() })
        genesisHandoff?.let { handoff ->
            require(handoff.target == GenesisHandoffTarget.BUILD_STUDIO) {
                "Only BUILD_STUDIO Genesis handoffs may enter BuildStudio"
            }
            require(!handoff.activationAllowed) { "Genesis handoff cannot authorize BuildStudio activation" }
        }
    }

    val id: String = StableFieldIds.fingerprint(
        "build-spec/v1",
        sourceCommit,
        gap.requirement.fingerprint(),
        gap.type.name,
        genesisHandoff?.payloadFingerprint.orEmpty(),
        *gap.candidateProviderIds.sorted().map { "candidate:$it" }.toTypedArray(),
        *allowedPathPrefixes.sorted().map { "allow:$it" }.toTypedArray(),
        *requiredTestPaths.sorted().map { "test:$it" }.toTypedArray(),
    )
}

data class BuildDesignSpec(
    val buildSpecId: String,
    val capability: CapabilityRequirement,
    val summary: String,
    val implementationNotes: List<String>,
    val plannedSourcePaths: Set<String>,
    val plannedTestPaths: Set<String>,
) {
    init {
        require(buildSpecId.isNotBlank())
        require(summary.isNotBlank())
        require(implementationNotes.none { it.isBlank() })
        require(plannedSourcePaths.isNotEmpty()) { "Build design requires source paths" }
        require(plannedTestPaths.isNotEmpty()) { "Build design requires test paths" }
        require(plannedSourcePaths.none { it.isBlank() } && plannedTestPaths.none { it.isBlank() })
    }

    val id: String = StableFieldIds.fingerprint(
        "build-design-spec/v1",
        buildSpecId,
        capability.fingerprint(),
        summary,
        *implementationNotes.sorted().map { "note:$it" }.toTypedArray(),
        *plannedSourcePaths.sorted().map { "source:$it" }.toTypedArray(),
        *plannedTestPaths.sorted().map { "test:$it" }.toTypedArray(),
    )
}

fun interface BuildDesignPlanner {
    suspend fun design(spec: BuildSpec): BuildDesignSpec
}

internal fun CapabilityRequirement.fingerprint(): String = StableFieldIds.fingerprint(
    "build-capability-requirement/v1",
    capabilityId.value,
    severity.name,
    *requiredInputs.sorted().map { "input:$it" }.toTypedArray(),
    *requiredOutputs.sorted().map { "output:$it" }.toTypedArray(),
)

private fun String.isCommitSha(): Boolean = matches(Regex("[0-9a-fA-F]{40}"))
