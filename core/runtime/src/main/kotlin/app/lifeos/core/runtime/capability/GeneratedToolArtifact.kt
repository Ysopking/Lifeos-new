package app.lifeos.core.runtime.capability

import app.lifeos.core.field.StableFieldIds
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

data class GeneratedToolArtifact(
    val toolId: String,
    val canonicalProgram: String,
    val sourceHash: String,
    val buildHash: String,
    val createdAt: Instant,
) {
    init {
        require(toolId.isNotBlank()) { "Generated-tool artifact id must not be blank" }
        require(canonicalProgram.isNotBlank()) { "Generated-tool artifact program must not be blank" }
        require(canonicalProgram.toByteArray(StandardCharsets.UTF_8).size <= MAX_PROGRAM_BYTES) {
            "Generated-tool artifact program exceeds size limit"
        }
        require(sourceHash.matches(BOUNDED_SOURCE_HASH)) {
            "Generated-tool artifact source hash must be typed bounded-v1 SHA-256"
        }
        require(buildHash.matches(SHA256)) { "Generated-tool artifact build hash must be SHA-256" }
        val program = GeneratedToolProgramCodec.decode(canonicalProgram)
        require(program.toolId == toolId) { "Generated-tool artifact program belongs to another tool" }
        require(typedSourceHash(canonicalProgram) == sourceHash) {
            "Generated-tool artifact source hash mismatch"
        }
        require(
            sha256((BUILD_DOMAIN + canonicalProgram).toByteArray(StandardCharsets.UTF_8)) == buildHash
        ) { "Generated-tool artifact build hash mismatch" }
    }

    val id: String = StableFieldIds.fingerprint(
        "generated-tool-artifact/v1",
        toolId,
        sourceHash,
        buildHash,
        createdAt.toString(),
        StableFieldIds.fingerprint("generated-tool-program-source/v1", canonicalProgram),
    )

    val activationAllowed: Boolean = false

    fun matches(record: GeneratedToolRecord): Boolean =
        record.manifest.toolId == toolId &&
            record.manifest.sourceHash == sourceHash &&
            record.manifest.buildHash == buildHash

    companion object {
        const val MAX_PROGRAM_BYTES = 32_768
        const val SOURCE_HASH_PREFIX = "bounded-v1:"
        internal const val BUILD_DOMAIN = "lifeos-bounded-tool-build/v1\n"
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val BOUNDED_SOURCE_HASH = Regex("bounded-v1:[0-9a-f]{64}")

        fun create(
            toolId: String,
            canonicalProgram: String,
            createdAt: Instant,
        ): GeneratedToolArtifact {
            val sourceHash = typedSourceHash(canonicalProgram)
            val buildHash = sha256((BUILD_DOMAIN + canonicalProgram).toByteArray(StandardCharsets.UTF_8))
            return GeneratedToolArtifact(toolId, canonicalProgram, sourceHash, buildHash, createdAt)
        }

        fun isBoundedSourceHash(value: String): Boolean = value.matches(BOUNDED_SOURCE_HASH)

        internal fun typedSourceHash(canonicalProgram: String): String =
            SOURCE_HASH_PREFIX + sha256(canonicalProgram.toByteArray(StandardCharsets.UTF_8))
    }
}

interface GeneratedToolArtifactRepository {
    suspend fun persist(artifact: GeneratedToolArtifact)
    suspend fun load(toolId: String): GeneratedToolArtifact?
    suspend fun loadAll(): List<GeneratedToolArtifact>
}

object GeneratedToolArtifactCodec {
    const val VERSION = 1
    private const val MAGIC = 0x4C544131 // LTA1
    private const val MAX_ARTIFACTS = 1_024
    private const val MAX_STRING_BYTES = GeneratedToolArtifact.MAX_PROGRAM_BYTES + 4_096

    fun encode(artifacts: List<GeneratedToolArtifact>): ByteArray {
        require(artifacts.size <= MAX_ARTIFACTS) { "Too many generated-tool artifacts" }
        require(artifacts.map { it.toolId }.distinct().size == artifacts.size) {
            "Generated-tool artifacts contain duplicate tool ids"
        }
        return ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { data ->
                data.writeInt(MAGIC)
                data.writeInt(VERSION)
                data.writeInt(artifacts.size)
                artifacts.sortedBy { it.toolId }.forEach { artifact ->
                    data.writeString(artifact.toolId)
                    data.writeString(artifact.canonicalProgram)
                    data.writeString(artifact.sourceHash)
                    data.writeString(artifact.buildHash)
                    data.writeString(artifact.createdAt.toString())
                }
            }
        }.toByteArray()
    }

    fun decode(bytes: ByteArray): List<GeneratedToolArtifact> =
        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            require(data.readInt() == MAGIC) { "Invalid generated-tool artifact codec magic" }
            require(data.readInt() == VERSION) { "Unsupported generated-tool artifact codec version" }
            val count = data.readInt()
            require(count in 0..MAX_ARTIFACTS) { "Invalid generated-tool artifact count" }
            val artifacts = buildList(count) {
                repeat(count) {
                    add(
                        GeneratedToolArtifact(
                            toolId = data.readString(),
                            canonicalProgram = data.readString(),
                            sourceHash = data.readString(),
                            buildHash = data.readString(),
                            createdAt = Instant.parse(data.readString()),
                        )
                    )
                }
            }
            require(data.read() == -1) { "Trailing bytes in generated-tool artifact payload" }
            require(artifacts.map { it.toolId }.distinct().size == artifacts.size) {
                "Generated-tool artifact payload contains duplicate tool ids"
            }
            artifacts.sortedBy { it.toolId }
        }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "Generated-tool artifact field too large" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val size = readInt()
        require(size in 1..MAX_STRING_BYTES) { "Invalid generated-tool artifact string size" }
        val bytes = ByteArray(size)
        readFully(bytes)
        return bytes.toString(StandardCharsets.UTF_8)
    }
}

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
