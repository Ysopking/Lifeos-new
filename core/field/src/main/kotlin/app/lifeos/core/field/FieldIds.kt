package app.lifeos.core.field

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@JvmInline
value class FieldDomainId(val value: String) {
    init { require(value.isNotBlank()) { "FieldDomainId must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class FieldNodeId(val value: String) {
    init { require(value.isNotBlank()) { "FieldNodeId must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class EvidenceId(val value: String) {
    init { require(value.isNotBlank()) { "EvidenceId must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class HypothesisId(val value: String) {
    init { require(value.isNotBlank()) { "HypothesisId must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class FieldRunId(val value: String) {
    init { require(value.isNotBlank()) { "FieldRunId must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class FieldSnapshotId(val value: String) {
    init { require(value.isNotBlank()) { "FieldSnapshotId must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class FieldRelationId(val value: String) {
    init { require(value.isNotBlank()) { "FieldRelationId must not be blank" } }
    override fun toString(): String = value
}

/**
 * Stable, content-derived ids used by field graphs and rehydration.
 * Parts are length-prefixed before hashing so ["ab", "c"] cannot collide structurally with
 * ["a", "bc"]. The function has no clock, random or platform dependencies.
 */
object StableFieldIds {
    fun domain(namespace: String): FieldDomainId =
        FieldDomainId("domain:${canonical(namespace)}")

    fun node(domain: FieldDomainId, kind: String, semanticKey: String): FieldNodeId =
        FieldNodeId(hashId("node", domain.value, kind, semanticKey))

    fun evidence(
        domain: FieldDomainId,
        sourcePhotonId: String,
        sourceRevision: Long,
        semanticKey: String,
        ordinal: Int = 0,
    ): EvidenceId {
        require(sourceRevision > 0) { "Source revision must be positive" }
        require(ordinal >= 0) { "Evidence ordinal must not be negative" }
        return EvidenceId(
            hashId(
                "evidence",
                domain.value,
                sourcePhotonId,
                sourceRevision.toString(),
                semanticKey,
                ordinal.toString(),
            ),
        )
    }

    fun hypothesis(domain: FieldDomainId, semanticKey: String): HypothesisId =
        HypothesisId(hashId("hypothesis", domain.value, semanticKey))

    fun relation(
        domain: FieldDomainId,
        source: FieldNodeId,
        target: FieldNodeId,
        relationType: String,
    ): FieldRelationId =
        FieldRelationId(hashId("relation", domain.value, source.value, target.value, relationType))

    fun snapshot(domain: FieldDomainId, sourceFingerprint: String): FieldSnapshotId =
        FieldSnapshotId(hashId("snapshot", domain.value, sourceFingerprint))

    fun run(domain: FieldDomainId, inputFingerprint: String): FieldRunId =
        FieldRunId(hashId("run", domain.value, inputFingerprint))

    fun fingerprint(vararg parts: String): String = hashHex(*parts)

    private fun hashId(prefix: String, vararg parts: String): String =
        "$prefix:${hashHex(*parts)}"

    private fun hashHex(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { raw ->
            val bytes = canonical(raw).toByteArray(StandardCharsets.UTF_8)
            val length = bytes.size
            digest.update(byteArrayOf(
                ((length ushr 24) and 0xff).toByte(),
                ((length ushr 16) and 0xff).toByte(),
                ((length ushr 8) and 0xff).toByte(),
                (length and 0xff).toByte(),
            ))
            digest.update(bytes)
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun canonical(value: String): String = value.trim().lowercase()
}
