package app.lifeos.core.runtime.artifact

import app.lifeos.core.model.Photon
import java.time.Instant

@JvmInline
value class ArtifactId(val value: String) {
    init {
        require(value.isNotBlank()) { "Artifact id must not be blank" }
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
) {
    init {
        require(contributions.isNotEmpty()) { "Collaborative artifact requires contributions" }
        require(finalizedAt >= request.requestedAt) {
            "Artifact finalization cannot predate its request"
        }
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
