package app.lifeos.core.runtime.artifact

import app.lifeos.core.model.Provenance
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

/**
 * Case-sensitive, length-prefixed hashing for artifact payloads. Field ids intentionally
 * canonicalize text for semantic matching; artifact identity must instead preserve exact bytes so
 * content that differs only by case remains distinct.
 */
internal object ArtifactFingerprints {
    fun fingerprint(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { raw ->
            val bytes = raw.toByteArray(StandardCharsets.UTF_8)
            val length = bytes.size
            digest.update(
                byteArrayOf(
                    ((length ushr 24) and 0xff).toByte(),
                    ((length ushr 16) and 0xff).toByte(),
                    ((length ushr 8) and 0xff).toByte(),
                    (length and 0xff).toByte(),
                )
            )
            digest.update(bytes)
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}

data class ArtifactContribution(
    val id: String,
    val module: String,
    val field: String,
    val source: String,
    val provenance: Provenance,
    val confidence: Double,
    val content: String,
    val contributedAt: Instant,
    val claimIds: Set<String> = emptySet(),
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
        require(claimIds.none { it.isBlank() }) { "Artifact contribution claim ids must not be blank" }
        require(contributedAt >= provenance.createdAt) {
            "Artifact contribution cannot predate its provenance"
        }
    }

    fun contentFingerprint(): String = ArtifactFingerprints.fingerprint(
        "artifact-contribution/v2",
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
        *claimIds.sorted().map { "claim:$it" }.toTypedArray(),
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
            claimIds: Set<String> = emptySet(),
        ): ArtifactContribution {
            val canonicalModule = module.trim()
            val canonicalField = field.trim()
            val canonicalSource = source.trim()
            val canonicalParents = provenance.parentIds.map { it.value }.sorted()
            val id = ArtifactFingerprints.fingerprint(
                "artifact-contribution-id/v2",
                canonicalModule,
                canonicalField,
                canonicalSource,
                provenance.source,
                provenance.actor,
                provenance.createdAt.toString(),
                contributedAt.toString(),
                java.lang.Double.toString(confidence),
                content,
                *canonicalParents.toTypedArray(),
                *claimIds.sorted().map { "claim:$it" }.toTypedArray(),
            )
            return ArtifactContribution(
                id = "artifact-contribution:$id",
                module = canonicalModule,
                field = canonicalField,
                source = canonicalSource,
                provenance = provenance,
                confidence = confidence,
                content = content,
                contributedAt = contributedAt,
                claimIds = claimIds.toSortedSet(),
            )
        }
    }
}
