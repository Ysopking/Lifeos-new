package app.lifeos.core.runtime.artifact

import app.lifeos.core.model.AssetRef
import app.lifeos.core.model.PhotonId
import java.time.Instant

/**
 * Append-only revision metadata for a collaborative artifact. Photon storage is current-value by
 * PhotonId, therefore every productive artifact revision receives a distinct PhotonId while this
 * logical revision chain preserves family identity and ancestry.
 */
data class ArtifactRevisionContext(
    val revision: Long = 1L,
    val parentPhotonId: PhotonId? = null,
    val inputPhotonIds: Set<PhotonId> = emptySet(),
) {
    init {
        require(revision > 0L) { "Artifact revision must be positive" }
        if (revision == 1L) {
            require(parentPhotonId == null) { "First artifact revision cannot have a parent revision" }
        } else {
            require(parentPhotonId != null) { "Artifact revision $revision requires a parent revision Photon" }
        }
    }

    val isLegacyInitialRevision: Boolean
        get() = revision == 1L && parentPhotonId == null && inputPhotonIds.isEmpty()
}

sealed interface ArtifactGenerationProfile {
    fun fingerprintParts(): List<String>
}

data class DocumentArtifactProfile(
    val format: String,
    val style: String? = null,
) : ArtifactGenerationProfile {
    init {
        require(format.isNotBlank()) { "Document artifact format must not be blank" }
        require(style == null || style.isNotBlank()) { "Document artifact style must not be blank" }
    }

    override fun fingerprintParts(): List<String> = listOf(
        "document",
        format,
        style.orEmpty(),
    )
}

data class CodeArtifactProfile(
    val language: String,
    val entrypoint: String? = null,
    val files: Set<String> = emptySet(),
) : ArtifactGenerationProfile {
    init {
        require(language.isNotBlank()) { "Code artifact language must not be blank" }
        require(entrypoint == null || entrypoint.isNotBlank()) { "Code artifact entrypoint must not be blank" }
        require(files.none { it.isBlank() }) { "Code artifact file paths must not be blank" }
    }

    override fun fingerprintParts(): List<String> = buildList {
        add("code")
        add(language)
        add(entrypoint.orEmpty())
        addAll(files.sorted())
    }
}

data class ImageArtifactProfile(
    val width: Int,
    val height: Int,
    val promptFingerprint: String,
    val model: String? = null,
) : ArtifactGenerationProfile {
    init {
        require(width > 0 && height > 0) { "Image artifact dimensions must be positive" }
        require(promptFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "Image prompt fingerprint must be lowercase SHA-256"
        }
        require(model == null || model.isNotBlank()) { "Image artifact model must not be blank" }
    }

    override fun fingerprintParts(): List<String> = listOf(
        "image",
        width.toString(),
        height.toString(),
        promptFingerprint,
        model.orEmpty(),
    )
}

data class ArtifactValidationEvidence(
    val id: String,
    val validator: String,
    val check: String,
    val passed: Boolean,
    val detail: String,
    val observedAt: Instant,
    val evidencePhotonIds: Set<PhotonId> = emptySet(),
) {
    init {
        require(id.isNotBlank()) { "Artifact validation evidence id must not be blank" }
        require(validator.isNotBlank()) { "Artifact validator must not be blank" }
        require(check.isNotBlank()) { "Artifact validation check must not be blank" }
        require(detail.isNotBlank()) { "Artifact validation detail must not be blank" }
    }

    fun contentFingerprint(): String = ArtifactFingerprints.fingerprint(
        "artifact-validation-evidence/v1",
        id,
        validator,
        check,
        passed.toString(),
        detail,
        observedAt.toString(),
        *evidencePhotonIds.map { it.value }.sorted().toTypedArray(),
    )

    companion object {
        fun create(
            validator: String,
            check: String,
            passed: Boolean,
            detail: String,
            observedAt: Instant,
            evidencePhotonIds: Set<PhotonId> = emptySet(),
        ): ArtifactValidationEvidence {
            val canonicalValidator = validator.trim()
            val canonicalCheck = check.trim()
            val canonicalDetail = detail.trim()
            val fingerprint = ArtifactFingerprints.fingerprint(
                "artifact-validation-evidence-id/v1",
                canonicalValidator,
                canonicalCheck,
                passed.toString(),
                canonicalDetail,
                observedAt.toString(),
                *evidencePhotonIds.map { it.value }.sorted().toTypedArray(),
            )
            return ArtifactValidationEvidence(
                id = "artifact-validation:$fingerprint",
                validator = canonicalValidator,
                check = canonicalCheck,
                passed = passed,
                detail = canonicalDetail,
                observedAt = observedAt,
                evidencePhotonIds = evidencePhotonIds,
            )
        }
    }
}

data class ArtifactOutputDescriptor(
    val asset: AssetRef,
    val profile: ArtifactGenerationProfile,
    val validationEvidence: List<ArtifactValidationEvidence> = emptyList(),
) {
    init {
        require(validationEvidence.map { it.id }.distinct().size == validationEvidence.size) {
            "Artifact validation evidence ids must be unique"
        }
    }

    fun contentFingerprint(): String = ArtifactFingerprints.fingerprint(
        "artifact-output/v1",
        asset.mediaType,
        asset.byteCount.toString(),
        asset.sha256,
        *profile.fingerprintParts().toTypedArray(),
        *validationEvidence
            .sortedBy { it.id }
            .map { it.contentFingerprint() }
            .toTypedArray(),
    )
}

data class ArtifactLifecycle(
    val revision: ArtifactRevisionContext = ArtifactRevisionContext(),
    val output: ArtifactOutputDescriptor? = null,
) {
    fun contentFingerprint(): String = ArtifactFingerprints.fingerprint(
        "artifact-lifecycle/v1",
        revision.revision.toString(),
        revision.parentPhotonId?.value.orEmpty(),
        *revision.inputPhotonIds.map { it.value }.sorted().toTypedArray(),
        output?.contentFingerprint().orEmpty(),
    )

    val isLegacyDefault: Boolean
        get() = revision.isLegacyInitialRevision && output == null
}
