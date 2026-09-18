package app.lifeos.core.runtime.escalation

import app.lifeos.core.runtime.health.HealthNodeId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant

object EscalationRecordLogCodec {
    private const val MAGIC = 0x4553434C
    private const val VERSION = 1
    private const val MAX_RECORDS = 4096
    private const val MAX_STRING_BYTES = 16 * 1024
    const val MAX_PAYLOAD_BYTES = 2 * 1024 * 1024

    fun encode(records: List<EscalationRecord>): ByteArray {
        require(records.size <= MAX_RECORDS) { "Escalation record log too large" }
        return ByteArrayOutputStream().let { output ->
            DataOutputStream(output).use { stream ->
                stream.writeInt(MAGIC)
                stream.writeInt(VERSION)
                stream.writeInt(records.size)
                records.forEach { writeRecord(stream, it) }
            }
            output.toByteArray()
        }.also { require(it.isNotEmpty() && it.size <= MAX_PAYLOAD_BYTES) }
    }

    fun encodeSegment(record: EscalationRecord): ByteArray = encode(listOf(record))

    fun decode(bytes: ByteArray): List<EscalationRecord> {
        require(bytes.isNotEmpty() && bytes.size <= MAX_PAYLOAD_BYTES) {
            "Invalid escalation payload size"
        }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == MAGIC) { "Invalid escalation payload magic" }
        require(input.readInt() == VERSION) { "Unsupported escalation payload version" }
        val count = input.readInt()
        require(count in 0..MAX_RECORDS) { "Invalid escalation record count" }
        val records = List(count) { readRecord(input) }
        require(input.available() == 0) { "Trailing escalation payload bytes" }
        return records
    }

    fun decodeSegment(bytes: ByteArray): EscalationRecord =
        decode(bytes).also { require(it.size == 1) { "Escalation segment must contain one record" } }.single()

    private fun writeRecord(output: DataOutputStream, record: EscalationRecord) {
        output.writeLong(record.revision)
        writeString(output, record.escalationId.value)
        writeString(output, record.nodeId.value)
        writeString(output, record.triggerFingerprint)
        writeString(output, record.type.name)
        writeString(output, record.recordedAt.toString())
        output.writeBoolean(record.level != null)
        record.level?.let { writeString(output, it.name) }
        writeNullableString(output, record.detail)
        val evidence = record.evidenceRefs.sorted()
        output.writeInt(evidence.size)
        evidence.forEach { writeString(output, it) }
    }

    private fun readRecord(input: DataInputStream): EscalationRecord {
        val revision = input.readLong()
        val escalationId = EscalationId(readString(input))
        val nodeId = HealthNodeId(readString(input))
        val triggerFingerprint = readString(input)
        val type = EscalationRecordType.valueOf(readString(input))
        val recordedAt = Instant.parse(readString(input))
        val level = if (input.readBoolean()) EscalationLevel.valueOf(readString(input)) else null
        val detail = readNullableString(input)
        val evidenceCount = input.readInt()
        require(evidenceCount in 0..MAX_RECORDS) { "Invalid escalation evidence count" }
        val evidence = buildSet {
            repeat(evidenceCount) { add(readString(input)) }
        }
        return EscalationRecord(
            revision = revision,
            escalationId = escalationId,
            nodeId = nodeId,
            triggerFingerprint = triggerFingerprint,
            type = type,
            recordedAt = recordedAt,
            level = level,
            detail = detail,
            evidenceRefs = evidence,
        )
    }

    private fun writeNullableString(output: DataOutputStream, value: String?) {
        output.writeBoolean(value != null)
        if (value != null) writeString(output, value)
    }

    private fun readNullableString(input: DataInputStream): String? =
        if (input.readBoolean()) readString(input) else null

    private fun writeString(output: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "Escalation string too large" }
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val length = input.readInt()
        require(length in 0..MAX_STRING_BYTES && length <= input.available()) {
            "Invalid escalation string length"
        }
        return ByteArray(length).also(input::readFully).toString(Charsets.UTF_8)
    }
}
