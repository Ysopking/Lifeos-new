package app.lifeos.core.runtime.artifact

import app.lifeos.core.model.AssetRef
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import java.time.Instant

@JvmInline
value class ArtifactId(val value: String) {
    init {
        require(value.isNotBlank()) { "Artifact id must not be blank" }
    }
}

@JvmInline
value class ArtifactRevisionId(val value: String) {
    init {
        require(value.matches(Regex("[0-9a-f]{64}"))) {
            "Artifact revision id must be a lowercase SHA-256 fingerprint"
        }
    }
}

data class ArtifactRevisionRef(
    val artifactId: ArtifactId,
    val revisionId: ArtifactRevisionId,
    val photonId: PhotonId,
)

data class ArtifactRevisionManifest(
    val id: ArtifactRevisionId,
    val parent: ArtifactRevisionRef? = null,
    val inputPhotonIds: Set<PhotonId>,
    val participatingModules: Set<String>,
    val stateHash: String,
    val materializedAsset: AssetRef? = null,
    val validation: ArtifactValidationEvidence,
    val semanticPlanFingerprint: String? = null,
) {
    init {
        require(inputPhotonIds.none { it.value.isBlank() }) {
            "Artifact revision input Photon ids must not be blank"
        }
        require(participatingModules.isNotEmpty()) {
            "Artifact revision requires participating modules"
        }
        require(participatingModules.none { it.isBlank() }) {
            "Artifact revision module ids must not be blank"
        }
        require(stateHash.matches(Regex("[0-9a-f]{64}"))) {
            "Artifact revision state hash must be lowercase hexadecimal"
        }
        require(
            semanticPlanFingerprint == null ||
                semanticPlanFingerprint.matches(Regex("[0-9a-f]{64}"))
        ) { "Artifact semantic plan fingerprint must be lowercase SHA-256" }
    }
}

enum class ArtifactKind {
    DOCUMENT,
    IMAGE,
    CODE,
    REPORT,
    OTHER,
}

/**
 * Stable input contract for one collaborative artifact run. Domain-specific renderers remain
 * downstream; this layer owns collaboration metadata, validation, provenance and Photon re-entry.
 */
data class CollaborativeArtifactRequest(
    val id: ArtifactId,
    val kind: ArtifactKind,
    val title: String,
    val targetMimeType: String,
    val requestedAt: Instant,
    val requiredFields: Set<String> = emptySet(),
) {
    init {
        require(title.isNotBlank()) { "Artifact title must not be blank" }
        require(targetMimeType.isNotBlank()) { "Artifact target MIME type must not be blank" }
        require(requiredFields.none { it.isBlank() }) { "Required artifact fields must not be blank" }
    }
}

data class CollaborativeArtifact(
    val request: CollaborativeArtifactRequest,
    val contributions: List<ArtifactContribution>,
    val photon: Photon,
    val finalizedAt: Instant,
    /** Null only for legacy callers/artifacts created before revision manifests were introduced. */
    val revision: ArtifactRevisionManifest? = null,
) {
    init {
        require(contributions.isNotEmpty()) { "Collaborative artifact requires contributions" }
        require(finalizedAt >= request.requestedAt) {
            "Artifact finalization cannot predate its request"
        }
    }

    fun revisionRef(): ArtifactRevisionRef {
        val manifest = requireNotNull(revision) { "Legacy artifact has no revision manifest" }
        return ArtifactRevisionRef(
            artifactId = request.id,
            revisionId = manifest.id,
            photonId = photon.id,
        )
    }
}

data class ArtifactReentryReceipt(
    val accepted: Boolean,
    val durableTaskId: String? = null,
)

data class ArtifactFinalizationResult(
    val artifact: CollaborativeArtifact,
    val reentry: ArtifactReentryReceipt,
)

enum class ArtifactValidationIssueCode {
    MISSING_CONTRIBUTIONS,
    INSUFFICIENT_MODULE_DIVERSITY,
    DUPLICATE_CONTRIBUTION_ID,
    MISSING_REQUIRED_FIELD,
    INVALID_PROVENANCE,
    INVALID_FINALIZATION_TIME,
}

data class ArtifactValidationIssue(
    val code: ArtifactValidationIssueCode,
    val message: String,
    val contributionId: String? = null,
)

data class ArtifactValidationResult(
    val issues: List<ArtifactValidationIssue>,
) {
    val isValid: Boolean get() = issues.isEmpty()
}

data class ArtifactValidationProfile(
    val minimumDistinctModules: Int,
) {
    init {
        require(minimumDistinctModules > 0) { "Minimum artifact module count must be positive" }
    }
}

data class ArtifactValidationEvidence(
    val profile: ArtifactValidationProfile,
    val result: ArtifactValidationResult,
) {
    init {
        require(result.isValid) { "Finalized artifact validation evidence must be valid" }
    }
}
