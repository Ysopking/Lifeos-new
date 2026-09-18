package app.lifeos.core.runtime.extension

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.buildstudio.CandidateArtifact

enum class ExtensionWorkshopExecutionPath {
    BUILD_STUDIO,
}

data class ExtensionWorkshopSpec(
    val extensionCandidateId: String,
    val sourceCommit: String,
    val requestedKinds: Set<ExtensionKind>,
    val allowedPathPrefixes: Set<String>,
    val requiredTestPaths: Set<String>,
    val executionPath: ExtensionWorkshopExecutionPath = ExtensionWorkshopExecutionPath.BUILD_STUDIO,
) {
    init {
        require(extensionCandidateId.isNotBlank()) {
            "Extension Workshop candidate id must not be blank"
        }
        require(sourceCommit.matches(Regex("[0-9a-fA-F]{40}"))) {
            "Extension Workshop source commit must be a 40-character git SHA"
        }
        require(requestedKinds.isNotEmpty()) {
            "Extension Workshop requires at least one requested extension kind"
        }
        require(allowedPathPrefixes.isNotEmpty() && allowedPathPrefixes.none { it.isBlank() }) {
            "Extension Workshop requires explicit allowed path prefixes"
        }
        require(requiredTestPaths.isNotEmpty() && requiredTestPaths.none { it.isBlank() }) {
            "Extension Workshop requires explicit test paths"
        }
    }

    val id: String = StableFieldIds.fingerprint(
        "extension-workshop-spec/v1",
        extensionCandidateId,
        sourceCommit.lowercase(),
        executionPath.name,
        *requestedKinds.map { it.name }.sorted().map { "kind:$it" }.toTypedArray(),
        *allowedPathPrefixes.sorted().map { "allow:$it" }.toTypedArray(),
        *requiredTestPaths.sorted().map { "test:$it" }.toTypedArray(),
    )

    val activationAllowed: Boolean = false
}

data class ExtensionWorkshopBuildEvidence(
    val id: String,
    val sourceCommit: String,
    val branchName: String,
    val branchHeadCommit: String,
    val debugArtifactRef: String,
    val debugArtifactSha256: String,
    val activationAllowed: Boolean,
) {
    init {
        require(id.isNotBlank())
        require(sourceCommit.matches(Regex("[0-9a-fA-F]{40}")))
        require(branchName.isNotBlank())
        require(branchHeadCommit.matches(Regex("[0-9a-fA-F]{40}")))
        require(debugArtifactRef.isNotBlank())
        require(debugArtifactSha256.matches(Regex("[0-9a-fA-F]{64}")))
        require(!activationAllowed) {
            "Extension Workshop may consume candidate evidence only, never activation authority"
        }
    }
}

fun CandidateArtifact.toExtensionWorkshopBuildEvidence(): ExtensionWorkshopBuildEvidence =
    ExtensionWorkshopBuildEvidence(
        id = id,
        sourceCommit = sourceCommit,
        branchName = branchName,
        branchHeadCommit = branchHeadCommit,
        debugArtifactRef = debugApkRef,
        debugArtifactSha256 = debugApkSha256,
        activationAllowed = activationAllowed,
    )

data class ExtensionWorkshopArtifact private constructor(
    val id: String,
    val specId: String,
    val extensionCandidateId: String,
    val buildEvidenceId: String,
    val sourceCommit: String,
    val branchHeadCommit: String,
    val requestedKinds: Set<ExtensionKind>,
) {
    init {
        require(id.isNotBlank())
        require(specId.isNotBlank())
        require(extensionCandidateId.isNotBlank())
        require(buildEvidenceId.isNotBlank())
        require(sourceCommit.matches(Regex("[0-9a-fA-F]{40}")))
        require(branchHeadCommit.matches(Regex("[0-9a-fA-F]{40}")))
        require(requestedKinds.isNotEmpty())
        require(id == expectedId()) {
            "Extension Workshop artifact id does not match content"
        }
    }

    val activationAllowed: Boolean
        get() = false

    val directRegistryMutationAllowed: Boolean
        get() = false

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "extension-workshop-artifact/v1",
        specId,
        extensionCandidateId,
        buildEvidenceId,
        sourceCommit.lowercase(),
        branchHeadCommit.lowercase(),
        *requestedKinds.map { it.name }.sorted().toTypedArray(),
    )

    private fun expectedId(): String = "extension-workshop:${fingerprint()}"

    companion object {
        fun create(
            spec: ExtensionWorkshopSpec,
            candidate: ExtensionCandidate,
            evidence: ExtensionWorkshopBuildEvidence,
        ): ExtensionWorkshopArtifact {
            require(spec.extensionCandidateId == candidate.id) {
                "Extension Workshop spec belongs to another candidate"
            }
            require(spec.requestedKinds == candidate.requestedKinds) {
                "Extension Workshop changed requested extension kinds"
            }
            require(evidence.sourceCommit.equals(spec.sourceCommit, ignoreCase = true)) {
                "Extension Workshop build evidence uses another source commit"
            }
            require(!evidence.activationAllowed)
            require(!candidate.activationAllowed)

            val provisional = StableFieldIds.fingerprint(
                "extension-workshop-artifact/v1",
                spec.id,
                candidate.id,
                evidence.id,
                evidence.sourceCommit.lowercase(),
                evidence.branchHeadCommit.lowercase(),
                *candidate.requestedKinds.map { it.name }.sorted().toTypedArray(),
            )
            return ExtensionWorkshopArtifact(
                id = "extension-workshop:$provisional",
                specId = spec.id,
                extensionCandidateId = candidate.id,
                buildEvidenceId = evidence.id,
                sourceCommit = evidence.sourceCommit,
                branchHeadCommit = evidence.branchHeadCommit,
                requestedKinds = candidate.requestedKinds,
            )
        }
    }
}

/**
 * B155 remains a composition boundary only. BuildStudio performs the authorized host build and the
 * existing LIFEOS OwnerPolicy/ResourceBudget/DecisionTrace infrastructure remains authoritative.
 * No parallel workshop ledger or autonomous control loop is introduced here.
 */
class ExtensionWorkshopPlanner {
    fun plan(
        candidate: ExtensionCandidate,
        sourceCommit: String,
        allowedPathPrefixes: Set<String>,
        requiredTestPaths: Set<String>,
    ): ExtensionWorkshopSpec = ExtensionWorkshopSpec(
        extensionCandidateId = candidate.id,
        sourceCommit = sourceCommit,
        requestedKinds = candidate.requestedKinds,
        allowedPathPrefixes = allowedPathPrefixes,
        requiredTestPaths = requiredTestPaths,
    )
}
