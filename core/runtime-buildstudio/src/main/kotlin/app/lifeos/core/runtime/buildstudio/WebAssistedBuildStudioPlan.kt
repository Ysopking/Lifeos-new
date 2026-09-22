package app.lifeos.core.runtime.buildstudio

import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.genesis.GenesisHandoff
import app.lifeos.core.runtime.genesis.GenesisHandoffTarget
import app.lifeos.core.runtime.web.WebAssistedToolWorkshopBrief
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class WebAssistedBuildStudioRequest(
    val sourceCommit: String,
    val gap: CapabilityGap,
    val genesisHandoff: GenesisHandoff,
    val toolWorkshopBrief: WebAssistedToolWorkshopBrief,
    val allowedPathPrefixes: Set<String>,
    val requiredTestPaths: Set<String>,
    val proposedSourcePaths: Set<String>,
    val proposedTestPaths: Set<String>,
    val fingerprint: String,
) {
    init {
        require(sourceCommit.matches(Regex("[0-9a-fA-F]{40}")))
        require(genesisHandoff.target == GenesisHandoffTarget.BUILD_STUDIO)
        require(!genesisHandoff.activationAllowed)
        require(gap.requirement.capabilityId == toolWorkshopBrief.requirement.capabilityId)
        require(allowedPathPrefixes.isNotEmpty() && allowedPathPrefixes.none(String::isBlank))
        require(requiredTestPaths.isNotEmpty() && requiredTestPaths.none(String::isBlank))
        require(proposedSourcePaths.isNotEmpty() && proposedSourcePaths.none(String::isBlank))
        require(proposedTestPaths.isNotEmpty() && proposedTestPaths.none(String::isBlank))
        require(requiredTestPaths.all { it in proposedTestPaths })
        require(
            fingerprint == requestFingerprint(
                sourceCommit,
                gap,
                genesisHandoff,
                toolWorkshopBrief,
                allowedPathPrefixes,
                requiredTestPaths,
                proposedSourcePaths,
                proposedTestPaths,
            )
        )
    }

    val repositoryAuthority: Boolean get() = false
    val patchAuthority: Boolean get() = false
    val buildAuthority: Boolean get() = false
    val permissionAuthority: Boolean get() = false
    val activationAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false

    companion object {
        fun create(
            sourceCommit: String,
            gap: CapabilityGap,
            genesisHandoff: GenesisHandoff,
            toolWorkshopBrief: WebAssistedToolWorkshopBrief,
            allowedPathPrefixes: Set<String>,
            requiredTestPaths: Set<String>,
            proposedSourcePaths: Set<String>,
            proposedTestPaths: Set<String>,
        ): WebAssistedBuildStudioRequest {
            val canonicalAllowed = allowedPathPrefixes.map(String::trim).filter(String::isNotBlank).toSortedSet()
            val canonicalRequiredTests = requiredTestPaths.map(String::trim).filter(String::isNotBlank).toSortedSet()
            val canonicalSources = proposedSourcePaths.map(String::trim).filter(String::isNotBlank).toSortedSet()
            val canonicalTests = proposedTestPaths.map(String::trim).filter(String::isNotBlank).toSortedSet()
            return WebAssistedBuildStudioRequest(
                sourceCommit = sourceCommit.lowercase(),
                gap = gap,
                genesisHandoff = genesisHandoff,
                toolWorkshopBrief = toolWorkshopBrief,
                allowedPathPrefixes = canonicalAllowed,
                requiredTestPaths = canonicalRequiredTests,
                proposedSourcePaths = canonicalSources,
                proposedTestPaths = canonicalTests,
                fingerprint = requestFingerprint(
                    sourceCommit.lowercase(),
                    gap,
                    genesisHandoff,
                    toolWorkshopBrief,
                    canonicalAllowed,
                    canonicalRequiredTests,
                    canonicalSources,
                    canonicalTests,
                ),
            )
        }
    }
}

data class WebAssistedBuildStudioPlan(
    val requestFingerprint: String,
    val buildSpec: BuildSpec,
    val design: BuildDesignSpec,
    val fingerprint: String,
) {
    init {
        require(requestFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(design.buildSpecId == buildSpec.id)
        require(design.capability == buildSpec.gap.requirement)
        require(
            fingerprint == planFingerprint(
                requestFingerprint,
                buildSpec,
                design,
            )
        )
    }

    val patchAuthority: Boolean get() = false
    val buildAuthority: Boolean get() = false
    val activationAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false
}

/**
 * B415 prepares an exact BuildStudio request/BuildSpec/BuildDesignSpec from B414 evidence.
 *
 * It does not create patch contents, touch a repository, run Gradle, collect artifacts, grant
 * permissions, or activate a candidate. Those operations remain inside the existing authorized
 * BuildStudio host and its BuildPathPolicy + TEST/LINT_DEBUG/ASSEMBLE_DEBUG verification chain.
 */
class WebAssistedBuildStudioPlanner(
    private val pathPolicy: BuildPathPolicy = BuildPathPolicy(),
) {
    fun plan(request: WebAssistedBuildStudioRequest): WebAssistedBuildStudioPlan {
        validatePaths(request)

        val spec = BuildSpec(
            sourceCommit = request.sourceCommit,
            gap = request.gap.copy(
                requirement = request.toolWorkshopBrief.requirement,
            ),
            allowedPathPrefixes = request.allowedPathPrefixes,
            requiredTestPaths = request.requiredTestPaths,
            genesisHandoff = request.genesisHandoff,
        )
        val notes = buildList {
            add("web-assisted-tool-workshop-brief:" + request.toolWorkshopBrief.fingerprint)
            add("openapi-tool-candidate:" + request.toolWorkshopBrief.toolCandidateFingerprint)
            add("openapi-spec-payload:" + request.toolWorkshopBrief.specPayloadSha256)
            request.toolWorkshopBrief.selectedOperationFingerprints.forEach {
                add("operation-evidence:" + it)
            }
            request.toolWorkshopBrief.documentationEvidence.forEach {
                add("documentation-evidence:" + it.fingerprint)
            }
            request.toolWorkshopBrief.implementationConstraints.forEach {
                add("constraint:" + it)
            }
            add("buildstudio-host-authority-required")
            add("buildstudio-gates-required:test,lintDebug,assembleDebug")
            add("activation-allowed:false")
        }.distinct().sorted()

        val design = BuildDesignSpec(
            buildSpecId = spec.id,
            capability = spec.gap.requirement,
            summary = "web-assisted-buildstudio:" + spec.gap.requirement.capabilityId.value,
            implementationNotes = notes,
            plannedSourcePaths = request.proposedSourcePaths,
            plannedTestPaths = request.proposedTestPaths,
        )
        return WebAssistedBuildStudioPlan(
            requestFingerprint = request.fingerprint,
            buildSpec = spec,
            design = design,
            fingerprint = planFingerprint(request.fingerprint, spec, design),
        )
    }

    private fun validatePaths(request: WebAssistedBuildStudioRequest) {
        val planned = request.proposedSourcePaths + request.proposedTestPaths
        planned.forEach { path ->
            require(isSafeRelativePath(path)) { "B415 planned path is unsafe: $path" }
            require(request.allowedPathPrefixes.any { prefix -> pathAllowed(path, prefix) }) {
                "B415 planned path is outside allowlist: $path"
            }
            require(!pathPolicy.isProtected(path)) {
                "B415 cannot plan a protected BuildStudio root: $path"
            }
        }
    }

    private fun pathAllowed(path: String, rawPrefix: String): Boolean {
        val prefix = rawPrefix.trim().removePrefix("./").trimEnd('/')
        return prefix.isNotBlank() && (path == prefix || path.startsWith("$prefix/"))
    }

    private fun isSafeRelativePath(path: String): Boolean {
        if (path.isBlank() || path.startsWith('/') || path.startsWith('\\') || '\\' in path) return false
        return path.split('/').none { it.isBlank() || it == "." || it == ".." }
    }
}

private fun requestFingerprint(
    sourceCommit: String,
    gap: CapabilityGap,
    genesisHandoff: GenesisHandoff,
    brief: WebAssistedToolWorkshopBrief,
    allowedPathPrefixes: Set<String>,
    requiredTestPaths: Set<String>,
    proposedSourcePaths: Set<String>,
    proposedTestPaths: Set<String>,
): String = b415Fingerprint(
    "web-assisted-buildstudio-request/v1",
    sourceCommit.lowercase(),
    gap.requirement.capabilityId.value,
    gap.requirement.severity.name,
    gap.type.name,
    genesisHandoff.payloadFingerprint,
    brief.fingerprint,
    allowedPathPrefixes.sorted().joinToString("\u001f"),
    requiredTestPaths.sorted().joinToString("\u001f"),
    proposedSourcePaths.sorted().joinToString("\u001f"),
    proposedTestPaths.sorted().joinToString("\u001f"),
)

private fun planFingerprint(
    requestFingerprint: String,
    buildSpec: BuildSpec,
    design: BuildDesignSpec,
): String = b415Fingerprint(
    "web-assisted-buildstudio-plan/v1",
    requestFingerprint,
    buildSpec.id,
    design.id,
)

private fun b415Fingerprint(domain: String, vararg parts: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun update(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(
            byteArrayOf(
                (bytes.size ushr 24).toByte(),
                (bytes.size ushr 16).toByte(),
                (bytes.size ushr 8).toByte(),
                bytes.size.toByte(),
            )
        )
        digest.update(bytes)
    }
    update(domain)
    parts.forEach(::update)
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
