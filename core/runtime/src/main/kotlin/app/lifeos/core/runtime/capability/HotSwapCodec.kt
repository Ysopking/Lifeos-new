package app.lifeos.core.runtime.capability

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant

object HotSwapEventLogCodec {
    private const val MAGIC = 0x48535731 // HSW1
    private const val VERSION = 1
    private const val MAX_EVENTS = 20_000
    private const val MAX_STRING_BYTES = 32 * 1024
    const val MAX_PAYLOAD_BYTES = 8 * 1024 * 1024

    fun encode(events: List<HotSwapEvent>): ByteArray {
        require(events.size <= MAX_EVENTS)
        require(events.map { it.revision } == (1L..events.size.toLong()).toList())
        return ByteArrayOutputStream().let { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeInt(events.size)
                events.forEach { event ->
                    out.writeLong(event.revision)
                    write(out, event.transactionId.value)
                    write(out, event.capabilityId.value)
                    write(out, event.previousToolId)
                    write(out, event.candidateToolId)
                    write(out, event.previousPromotionEvidenceId)
                    write(out, event.candidatePromotionEvidenceId)
                    write(out, event.type.name)
                    write(out, event.recordedAt.toString())
                    writeNullableLong(out, event.ownerPolicyRevision)
                    writeNullable(out, event.worldSnapshotId)
                    writeNullable(out, event.detail)
                }
            }
            bytes.toByteArray()
        }.also { require(it.size <= MAX_PAYLOAD_BYTES) }
    }

    fun decode(bytes: ByteArray): List<HotSwapEvent> {
        require(bytes.isNotEmpty() && bytes.size <= MAX_PAYLOAD_BYTES)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == MAGIC) { "Invalid hot-swap payload magic" }
        require(input.readInt() == VERSION) { "Unsupported hot-swap payload version" }
        val count = input.readInt()
        require(count in 0..MAX_EVENTS)
        val events = List(count) { index ->
            HotSwapEvent(
                revision = input.readLong(),
                transactionId = HotSwapTransactionId(read(input)),
                capabilityId = CapabilityId(read(input)),
                previousToolId = read(input),
                candidateToolId = read(input),
                previousPromotionEvidenceId = read(input),
                candidatePromotionEvidenceId = read(input),
                type = enumValueOf(read(input)),
                recordedAt = Instant.parse(read(input)),
                ownerPolicyRevision = readNullableLong(input),
                worldSnapshotId = readNullable(input),
                detail = readNullable(input),
            ).also { require(it.revision == index + 1L) }
        }
        require(input.available() == 0) { "Trailing hot-swap payload bytes" }
        return events
    }

    private fun writeNullableLong(out: DataOutputStream, value: Long?) {
        out.writeBoolean(value != null)
        if (value != null) out.writeLong(value)
    }

    private fun readNullableLong(input: DataInputStream): Long? =
        if (input.readBoolean()) input.readLong() else null

    private fun writeNullable(out: DataOutputStream, value: String?) {
        out.writeBoolean(value != null)
        if (value != null) write(out, value)
    }

    private fun readNullable(input: DataInputStream): String? = if (input.readBoolean()) read(input) else null

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
