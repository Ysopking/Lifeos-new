package app.lifeos.core.runtime.artifact

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.Provenance
import java.time.Instant

data class ArtifactContribution(
    val id: String,
    val module: String,
    val field: String,
    val source: String,
    val provenance: Provenance,
    val confidence: Double,
    val content: String,
    val contributedAt: Instant,
) {
    init {
        require(id.isNotBlank()) { "Artifact contribution id must not be blank" }
        require(module.isNotBlank()) { "Artifact contribution module must not be blank" }
        require(field.isNotBlank()) { "Artifact contribution field must not be blank" }
        require(source.isNotBlank()) { "Artifact contribution source must not be blank" }
        require(provenance.source.isNotBlank()) { "Artifact contribution provenance source must not be blank" }
        require(provenance.actor.isNotBlank()) { "Artifact contribution provenance actor must not be blank" }
        require(confidence.isFinite() && confidence in 0.0..1.0) {
            "Artifact contribution confidence must be normalized"
        }
        require(content.isNotBlank()) { "Artifact contribution content must not be blank" }
        require(contributedAt >= provenance.createdAt) {
            "Artifact contribution cannot predate its provenance"
        }
    }

    fun contentFingerprint(): String = StableFieldIds.fingerprint(
        "artifact-contribution/v1",
        id,
        module,
        field,
        source,
        provenance.source,
        provenance.actor,
        provenance.createdAt.toString(),
        contributedAt.toString(),
        java.lang.Double.toString(confidence),
        content,
        *provenance.parentIds.map { it.value }.sorted().toTypedArray(),
    )

    companion object {
        fun create(
            module: String,
            field: String,
            source: String,
            provenance: Provenance,
            confidence: Double,
            content: String,
            contributedAt: Instant = provenance.createdAt,
        ): ArtifactContribution {
            val canonicalParents = provenance.parentIds.map { it.value }.sorted()
            val id = StableFieldIds.fingerprint(
                "artifact-contribution-id/v1",
                module.trim(),
                field.trim(),
                source.trim(),
                provenance.source.trim(),
                provenance.actor.trim(),
                provenance.createdAt.toString(),
                contributedAt.toString(),
                java.lang.Double.toString(confidence),
                content,
                *canonicalParents.toTypedArray(),
            )
            return ArtifactContribution(
                id = "artifact-contribution:$id",
                module = module.trim(),
                field = field.trim(),
                source = source.trim(),
                provenance = provenance,
                confidence = confidence,
                content = content,
                contributedAt = contributedAt,
            )
        }
    }
}
