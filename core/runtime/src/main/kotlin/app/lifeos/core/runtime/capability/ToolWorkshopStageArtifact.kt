package app.lifeos.core.runtime.capability

import app.lifeos.core.field.StableFieldIds
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant

data class ToolWorkshopStageArtifact(
    val jobId: ToolWorkshopJobId,
    val stage: ToolWorkshopJobState,
    val payload: String,
    val createdAt: Instant,
) {
    init {
        require(stage in ARTIFACT_STAGES) { "$stage cannot own a workshop stage artifact" }
        require(payload.isNotBlank()) { "Workshop stage artifact payload must not be blank" }
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
            "Workshop stage artifact payload exceeds size limit"
        }
    }

    val fingerprint: String = StableFieldIds.fingerprint(
        "tool-workshop-stage-artifact/v1",
        jobId.value,
        stage.name,
        payload,
    )

    companion object {
        const val MAX_PAYLOAD_BYTES = 512 * 1024
        val ARTIFACT_STAGES = setOf(
            ToolWorkshopJobState.SPECIFIED,
            ToolWorkshopJobState.DESIGNED,
            ToolWorkshopJobState.IMPLEMENTED,
            ToolWorkshopJobState.BUILT,
            ToolWorkshopJobState.TESTED,
            ToolWorkshopJobState.SECURITY_VALIDATED,
            ToolWorkshopJobState.VERIFIED,
            ToolWorkshopJobState.TRIAL_READY,
        )
    }
}

interface ToolWorkshopStageArtifactRepository {
    suspend fun persist(artifact: ToolWorkshopStageArtifact)
    suspend fun load(jobId: ToolWorkshopJobId, stage: ToolWorkshopJobState): ToolWorkshopStageArtifact?
    suspend fun loadAll(jobId: ToolWorkshopJobId): List<ToolWorkshopStageArtifact>
}

object ToolWorkshopStageArtifactCodec {
    private const val MAGIC = 0x54574131 // TWA1
    private const val VERSION = 1
    private const val MAX_ARTIFACTS = 8_192
    private const val MAX_STRING_BYTES = ToolWorkshopStageArtifact.MAX_PAYLOAD_BYTES
    const val MAX_FILE_BYTES = 32 * 1024 * 1024

    fun encode(artifacts: List<ToolWorkshopStageArtifact>): ByteArray {
        require(artifacts.size <= MAX_ARTIFACTS)
        require(artifacts.map { it.jobId to it.stage }.distinct().size == artifacts.size) {
            "Duplicate ToolWorkshop stage artifact key"
        }
        return ByteArrayOutputStream().let { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeInt(artifacts.size)
                artifacts.sortedWith(compareBy<ToolWorkshopStageArtifact> { it.jobId.value }.thenBy { it.stage.name })
                    .forEach { artifact ->
                        write(out, artifact.jobId.value)
                        write(out, artifact.stage.name)
                        write(out, artifact.payload)
                        write(out, artifact.createdAt.toString())
                        write(out, artifact.fingerprint)
                    }
            }
            bytes.toByteArray()
        }.also { require(it.size <= MAX_FILE_BYTES) }
    }

    fun decode(bytes: ByteArray): List<ToolWorkshopStageArtifact> {
        require(bytes.isNotEmpty() && bytes.size <= MAX_FILE_BYTES)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == MAGIC) { "Invalid ToolWorkshop stage artifact magic" }
        require(input.readInt() == VERSION) { "Unsupported ToolWorkshop stage artifact version" }
        val count = input.readInt()
        require(count in 0..MAX_ARTIFACTS)
        val artifacts = List(count) {
            val artifact = ToolWorkshopStageArtifact(
                jobId = ToolWorkshopJobId(read(input)),
                stage = enumValueOf(read(input)),
                payload = read(input),
                createdAt = Instant.parse(read(input)),
            )
            require(read(input) == artifact.fingerprint) {
                "ToolWorkshop stage artifact fingerprint mismatch"
            }
            artifact
        }
        require(input.available() == 0) { "Trailing ToolWorkshop stage artifact bytes" }
        require(artifacts.map { it.jobId to it.stage }.distinct().size == artifacts.size) {
            "Duplicate ToolWorkshop stage artifacts in payload"
        }
        return artifacts.sortedWith(compareBy<ToolWorkshopStageArtifact> { it.jobId.value }.thenBy { it.stage.name })
    }

    private fun write(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun read(input: DataInputStream): String {
        val length = input.readInt()
        require(length in 0..MAX_STRING_BYTES && length <= input.available())
        return ByteArray(length).also(input::readFully).toString(Charsets.UTF_8)
    }
}
