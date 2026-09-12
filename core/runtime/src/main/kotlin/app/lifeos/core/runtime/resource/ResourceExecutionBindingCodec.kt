package app.lifeos.core.runtime.resource

import app.lifeos.core.runtime.trace.DecisionTraceId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant

object ResourceExecutionBindingCodec {
    private const val MAGIC = 0x52454231 // REB1
    private const val VERSION = 1
    const val MAX_PAYLOAD_BYTES = 64 * 1024
    private const val MAX_STRING_BYTES = 8 * 1024

    fun encode(binding: ResourceExecutionBinding): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
            data.writeString(binding.traceId.value)
            data.writeString(binding.domain.name)
            data.writeString(binding.operationId)
            data.writeString(binding.accountId.value)
            data.writeString(binding.reservationId.value)
            data.writeBoolean(binding.authoritativeStateId != null)
            binding.authoritativeStateId?.let(data::writeString)
            data.writeLong(binding.revision)
            data.writeString(binding.boundAt.toString())
        }
        return output.toByteArray().also {
            require(it.size <= MAX_PAYLOAD_BYTES) { "Resource execution binding payload too large" }
        }
    }

    fun decode(bytes: ByteArray): ResourceExecutionBinding {
        require(bytes.size <= MAX_PAYLOAD_BYTES) { "Resource execution binding payload too large" }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == MAGIC) { "Invalid resource execution binding magic" }
        require(input.readInt() == VERSION) { "Unsupported resource execution binding version" }
        val binding = ResourceExecutionBinding(
            traceId = DecisionTraceId(input.readString()),
            domain = enumValueOf<ResourceBudgetDomain>(input.readString()),
            operationId = input.readString(),
            accountId = ResourceBudgetAccountId(input.readString()),
            reservationId = ResourceBudgetReservationId(input.readString()),
            authoritativeStateId = if (input.readBoolean()) input.readString() else null,
            revision = input.readLong(),
            boundAt = Instant.parse(input.readString()),
        )
        require(input.available() == 0) { "Trailing bytes in resource execution binding" }
        return binding
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "Resource execution binding string too large" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val size = readInt()
        require(size in 0..MAX_STRING_BYTES) { "Invalid resource execution binding string size" }
        val bytes = ByteArray(size)
        readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }
}

interface ResourceExecutionBindingRepository {
    suspend fun load(operationId: String): ResourceExecutionBinding?
    suspend fun create(binding: ResourceExecutionBinding): Boolean
    suspend fun compareAndSet(
        operationId: String,
        expectedRevision: Long,
        updated: ResourceExecutionBinding,
    ): Boolean
}
