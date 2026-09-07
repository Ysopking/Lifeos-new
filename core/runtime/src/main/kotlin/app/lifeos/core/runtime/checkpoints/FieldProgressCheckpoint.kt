package app.lifeos.core.runtime.checkpoints

import app.lifeos.core.runtime.ForceField
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class FieldProgressCheckpoint(
    val fieldSignature: String,
    val completedFieldIndexes: Set<Int>,
) {
    init {
        require(fieldSignature.isNotBlank()) { "Field checkpoint signature must not be blank" }
        require(completedFieldIndexes.size <= MAX_FIELDS) { "Too many completed fields in checkpoint" }
        require(completedFieldIndexes.all { it >= 0 }) { "Completed field indexes must not be negative" }
    }

    companion object {
        const val MAX_FIELDS = 4096
    }
}

object FieldProgressCheckpointCodec {
    private const val VERSION = 1
    private const val MAX_SIGNATURE_BYTES = 128
    private const val MAX_PAYLOAD_BYTES = 64 * 1024

    fun signature(fields: List<ForceField>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fields.forEachIndexed { index, field ->
            digest.update(index.toString().toByteArray(StandardCharsets.UTF_8))
            digest.update(':'.code.toByte())
            digest.update(field.javaClass.name.toByteArray(StandardCharsets.UTF_8))
            digest.update('\n'.code.toByte())
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    fun encode(progress: FieldProgressCheckpoint): ByteArray {
        val signatureBytes = progress.fieldSignature.toByteArray(StandardCharsets.UTF_8)
        require(signatureBytes.size <= MAX_SIGNATURE_BYTES) { "Field checkpoint signature too large" }

        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { stream ->
            stream.writeInt(VERSION)
            stream.writeInt(signatureBytes.size)
            stream.write(signatureBytes)
            val indexes = progress.completedFieldIndexes.sorted()
            stream.writeInt(indexes.size)
            indexes.forEach(stream::writeInt)
        }
        return output.toByteArray().also {
            require(it.size <= MAX_PAYLOAD_BYTES) { "Field checkpoint payload too large" }
        }
    }

    fun decode(payload: ByteArray): FieldProgressCheckpoint {
        require(payload.isNotEmpty()) { "Field checkpoint payload must not be empty" }
        require(payload.size <= MAX_PAYLOAD_BYTES) { "Field checkpoint payload too large" }

        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == VERSION) { "Unsupported field checkpoint version" }
            val signatureSize = input.readInt()
            require(signatureSize in 1..MAX_SIGNATURE_BYTES) { "Invalid field checkpoint signature length" }
            val signature = ByteArray(signatureSize).also(input::readFully).toString(StandardCharsets.UTF_8)
            val count = input.readInt()
            require(count in 0..FieldProgressCheckpoint.MAX_FIELDS) { "Invalid completed field count" }
            val indexes = LinkedHashSet<Int>(count)
            repeat(count) {
                val index = input.readInt()
                require(index >= 0) { "Completed field index must not be negative" }
                require(indexes.add(index)) { "Duplicate completed field index" }
            }
            require(input.available() == 0) { "Trailing field checkpoint data" }
            FieldProgressCheckpoint(signature, indexes)
        }
    }
}
