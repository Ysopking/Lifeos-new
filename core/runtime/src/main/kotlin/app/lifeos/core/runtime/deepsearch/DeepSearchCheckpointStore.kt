package app.lifeos.core.runtime.deepsearch

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant

data class DeepSearchStoredCheckpoint(
    val missionId: DeepSearchMissionId,
    val revision: Long,
    val checkpoint: DeepSearchPlannerCheckpoint,
    val recordedAt: Instant,
) {
    init {
        require(revision > 0L)
    }
}

data class DeepSearchCheckpointLoadReport(
    val value: DeepSearchStoredCheckpoint?,
    val unreadableEntries: List<String> = emptyList(),
)

interface DeepSearchCheckpointRepository {
    suspend fun load(missionId: DeepSearchMissionId): DeepSearchCheckpointLoadReport
    suspend fun compareAndSet(
        missionId: DeepSearchMissionId,
        expectedRevision: Long,
        updated: DeepSearchStoredCheckpoint,
    ): Boolean
}

class DeepSearchCheckpointStore(
    private val repository: DeepSearchCheckpointRepository,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun load(missionId: DeepSearchMissionId): DeepSearchStoredCheckpoint? {
        val report = repository.load(missionId)
        check(report.unreadableEntries.isEmpty()) {
            "DeepSearch checkpoint is unreadable: ${report.unreadableEntries.joinToString(",")}" 
        }
        return report.value
    }

    suspend fun persist(
        missionId: DeepSearchMissionId,
        checkpoint: DeepSearchPlannerCheckpoint,
    ): DeepSearchStoredCheckpoint {
        repeat(MAX_CAS_ATTEMPTS) {
            val current = load(missionId)
            if (current?.checkpoint?.fingerprint() == checkpoint.fingerprint()) return current
            val expected = current?.revision ?: 0L
            val updated = DeepSearchStoredCheckpoint(
                missionId = missionId,
                revision = expected + 1L,
                checkpoint = checkpoint,
                recordedAt = now(),
            )
            if (repository.compareAndSet(missionId, expected, updated)) return updated
        }
        error("DeepSearch checkpoint CAS retries exhausted")
    }

    private companion object {
        const val MAX_CAS_ATTEMPTS = 32
    }
}

object DeepSearchStoredCheckpointCodec {
    private const val MAGIC = 0x44534353 // DSCS
    private const val VERSION = 1
    const val MAX_PAYLOAD_BYTES = DeepSearchPlannerCheckpointCodec.MAX_PAYLOAD_BYTES + 256 * 1024

    fun encode(value: DeepSearchStoredCheckpoint): ByteArray {
        val checkpoint = DeepSearchPlannerCheckpointCodec.encode(value.checkpoint)
        return ByteArrayOutputStream().let { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                write(out, value.missionId.value)
                out.writeLong(value.revision)
                write(out, value.recordedAt.toString())
                out.writeInt(checkpoint.size)
                out.write(checkpoint)
                write(out, value.checkpoint.fingerprint())
            }
            bytes.toByteArray()
        }.also { require(it.size <= MAX_PAYLOAD_BYTES) }
    }

    fun decode(bytes: ByteArray): DeepSearchStoredCheckpoint {
        require(bytes.isNotEmpty() && bytes.size <= MAX_PAYLOAD_BYTES)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == MAGIC)
        require(input.readInt() == VERSION)
        val missionId = DeepSearchMissionId(read(input))
        val revision = input.readLong()
        val recordedAt = Instant.parse(read(input))
        val checkpointLength = input.readInt()
        require(checkpointLength in 1..DeepSearchPlannerCheckpointCodec.MAX_PAYLOAD_BYTES)
        require(checkpointLength <= input.available())
        val checkpointBytes = ByteArray(checkpointLength).also(input::readFully)
        val checkpoint = DeepSearchPlannerCheckpointCodec.decode(checkpointBytes)
        require(read(input) == checkpoint.fingerprint())
        require(input.available() == 0) { "Trailing DeepSearch stored checkpoint bytes" }
        return DeepSearchStoredCheckpoint(missionId, revision, checkpoint, recordedAt)
    }

    private fun write(out: DataOutputStream, value: String) {
        val data = value.toByteArray(Charsets.UTF_8)
        require(data.size <= 128 * 1024)
        out.writeInt(data.size)
        out.write(data)
    }

    private fun read(input: DataInputStream): String {
        val size = input.readInt()
        require(size in 0..128 * 1024 && size <= input.available())
        return ByteArray(size).also(input::readFully).toString(Charsets.UTF_8)
    }
}
