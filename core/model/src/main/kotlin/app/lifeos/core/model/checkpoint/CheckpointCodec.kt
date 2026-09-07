package app.lifeos.core.model.checkpoint

import app.lifeos.core.model.task.TaskId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant

object CheckpointCodec {
    const val VERSION = 1
    private const val MAX_CONTAINER_BYTES = TaskCheckpoint.MAX_PAYLOAD_BYTES + 1024

    fun encode(value: TaskCheckpoint): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeUTF(value.id.value)
            out.writeUTF(value.taskId.value)
            out.writeLong(value.sequence)
            out.writeLong(value.runtimeGeneration)
            out.writeLong(value.createdAt.epochSecond)
            out.writeInt(value.createdAt.nano)
            out.writeInt(value.payload.size)
            out.write(value.payload)
        }
        require(bytes.size() <= MAX_CONTAINER_BYTES) { "Checkpoint exceeds size limit" }
    }.toByteArray()

    fun decode(bytes: ByteArray, version: Int = VERSION): TaskCheckpoint {
        require(version == VERSION) { "Unsupported checkpoint format" }
        require(bytes.size <= MAX_CONTAINER_BYTES) { "Checkpoint exceeds size limit" }

        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val id = CheckpointId(input.readUTF())
            val taskId = TaskId(input.readUTF())
            val sequence = input.readLong()
            val runtimeGeneration = input.readLong()
            val seconds = input.readLong()
            val nanos = input.readInt().also { require(it in 0..999_999_999) }
            val payloadSize = input.readInt().also {
                require(it in 1..TaskCheckpoint.MAX_PAYLOAD_BYTES && it <= input.available()) {
                    "Invalid checkpoint payload length"
                }
            }
            val payload = ByteArray(payloadSize).also { input.readFully(it) }
            require(input.available() == 0) { "Trailing checkpoint data" }

            TaskCheckpoint(
                id = id,
                taskId = taskId,
                sequence = sequence,
                runtimeGeneration = runtimeGeneration,
                payload = payload,
                createdAt = Instant.ofEpochSecond(seconds, nanos.toLong()),
            )
        }
    }
}
