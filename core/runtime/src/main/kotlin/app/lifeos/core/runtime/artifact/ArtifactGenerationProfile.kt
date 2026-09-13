package app.lifeos.core.runtime.artifact

/**
 * Typed generation intent for materialized collaborative artifacts. The profile is metadata only:
 * binary bodies stay in the asset store and are referenced through AssetRef on the revision manifest.
 */
sealed interface ArtifactGenerationProfile {
    val type: String

    fun fingerprintParts(): List<String>

    fun requireCompatible(kind: ArtifactKind)
}

data class DocumentArtifactProfile(
    val format: String,
    val style: String? = null,
) : ArtifactGenerationProfile {
    init {
        require(format.isNotBlank()) { "Document artifact format must not be blank" }
        require(style == null || style.isNotBlank()) { "Document artifact style must not be blank" }
    }

    override val type: String = "document"

    override fun fingerprintParts(): List<String> = listOf(
        type,
        format,
        style.orEmpty(),
    )

    override fun requireCompatible(kind: ArtifactKind) {
        require(kind == ArtifactKind.DOCUMENT || kind == ArtifactKind.REPORT) {
            "Document generation profile requires DOCUMENT or REPORT artifact kind"
        }
    }
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

    override val type: String = "code"

    override fun fingerprintParts(): List<String> = buildList {
        add(type)
        add(language)
        add(entrypoint.orEmpty())
        addAll(files.sorted())
    }

    override fun requireCompatible(kind: ArtifactKind) {
        require(kind == ArtifactKind.CODE) {
            "Code generation profile requires CODE artifact kind"
        }
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

    override val type: String = "image"

    override fun fingerprintParts(): List<String> = listOf(
        type,
        width.toString(),
        height.toString(),
        promptFingerprint,
        model.orEmpty(),
    )

    override fun requireCompatible(kind: ArtifactKind) {
        require(kind == ArtifactKind.IMAGE) {
            "Image generation profile requires IMAGE artifact kind"
        }
    }
}
