package app.lifeos.core.model

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@JvmInline
value class CausalTraceId(val value: String) {
    init { require(value.isNotBlank()) { "CausalTraceId must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class PhotonBranchId(val value: String) {
    init { require(value.isNotBlank()) { "PhotonBranchId must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class ModuleProcessingId(val value: String) {
    init { require(value.isNotBlank()) { "ModuleProcessingId must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class ConvergenceId(val value: String) {
    init { require(value.isNotBlank()) { "ConvergenceId must not be blank" } }
    override fun toString(): String = value
}

@JvmInline
value class CognitiveStateHash(val value: String) {
    init { require(value.matches(Regex("[0-9a-f]{64}"))) { "CognitiveStateHash must be a SHA-256 hex digest" } }
    override fun toString(): String = value
}

@JvmInline
value class LogicalTick(val value: Long) {
    init { require(value >= 0) { "LogicalTick must not be negative" } }
    fun next(): LogicalTick = LogicalTick(Math.addExact(value, 1L))
}

/**
 * Immutable replay inputs for one causal processing step.
 * Wall-clock time and random UUIDs intentionally do not participate.
 */
data class DeterminismContext(
    val traceId: CausalTraceId,
    val logicalTick: LogicalTick,
    val inputHash: CognitiveStateHash,
    val parentStateHash: CognitiveStateHash,
    val runtimeVersion: String,
    val policyVersion: String,
    val randomSeed: Long,
    val parametersHash: CognitiveStateHash,
) {
    init {
        require(runtimeVersion.isNotBlank()) { "Runtime version must not be blank" }
        require(policyVersion.isNotBlank()) { "Policy version must not be blank" }
    }
}

/**
 * Stable identifiers for replay-sensitive cognition.
 * Values are length-prefixed before SHA-256 hashing, preserving exact UTF-8 content and
 * preventing structural collisions such as ["ab", "c"] vs ["a", "bc"].
 */
object StableCognitiveIds {
    fun trace(
        rootPhotonId: PhotonId,
        rootRevision: Long,
        inputHash: CognitiveStateHash,
        namespace: String = "lifeos",
    ): CausalTraceId {
        require(rootRevision > 0) { "Root revision must be positive" }
        require(namespace.isNotBlank()) { "Trace namespace must not be blank" }
        return CausalTraceId(
            prefixedHash("trace", namespace, rootPhotonId.value, rootRevision.toString(), inputHash.value),
        )
    }

    fun branch(
        traceId: CausalTraceId,
        parentPhotonId: PhotonId,
        moduleKey: String,
        ordinal: Int,
    ): PhotonBranchId {
        require(moduleKey.isNotBlank()) { "Module key must not be blank" }
        require(ordinal >= 0) { "Branch ordinal must not be negative" }
        return PhotonBranchId(
            prefixedHash("branch", traceId.value, parentPhotonId.value, moduleKey, ordinal.toString()),
        )
    }

    fun moduleProcessing(
        traceId: CausalTraceId,
        branchId: PhotonBranchId,
        moduleFingerprint: String,
        inputPhotonId: PhotonId,
        inputRevision: Long,
    ): ModuleProcessingId {
        require(moduleFingerprint.isNotBlank()) { "Module fingerprint must not be blank" }
        require(inputRevision > 0) { "Input revision must be positive" }
        return ModuleProcessingId(
            prefixedHash(
                "module-processing",
                traceId.value,
                branchId.value,
                moduleFingerprint,
                inputPhotonId.value,
                inputRevision.toString(),
            ),
        )
    }

    fun convergence(
        traceId: CausalTraceId,
        branchIds: Collection<PhotonBranchId>,
    ): ConvergenceId {
        require(branchIds.isNotEmpty()) { "Convergence requires at least one branch" }
        val ordered = branchIds.map { it.value }.distinct().sorted()
        return ConvergenceId(prefixedHash("convergence", traceId.value, *ordered.toTypedArray()))
    }

    fun stateHash(vararg parts: String): CognitiveStateHash = CognitiveStateHash(hashHex(*parts))

    fun stateHash(parts: Iterable<String>): CognitiveStateHash =
        CognitiveStateHash(hashHex(*parts.toList().toTypedArray()))

    fun fingerprint(vararg parts: String): String = hashHex(*parts)

    private fun prefixedHash(prefix: String, vararg parts: String): String =
        "$prefix:${hashHex(*parts)}"

    private fun hashHex(vararg parts: String): String {
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
                ),
            )
            digest.update(bytes)
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
