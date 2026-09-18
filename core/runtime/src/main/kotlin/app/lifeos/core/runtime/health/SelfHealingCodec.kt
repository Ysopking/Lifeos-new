package app.lifeos.core.runtime.health

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant

object SelfHealingEventLogCodec {
    private const val MAGIC = 0x53484c31 // SHL1
    private const val VERSION = 1
    private const val MAX_EVENTS = 20_000
    private const val MAX_STRING_BYTES = 32 * 1024
    const val MAX_PAYLOAD_BYTES = 8 * 1024 * 1024

    fun encode(events: List<SelfHealingEvent>): ByteArray {
        require(events.map { it.revision } == (1L..events.size.toLong()).toList()) {
            "SelfHealingEvent full-log revisions must be contiguous"
        }
        return encodePayload(events)
    }

    /** Encodes one immutable segment while preserving its global ledger revision. */
    fun encodeSegment(event: SelfHealingEvent): ByteArray = encodePayload(listOf(event))

    private fun encodePayload(events: List<SelfHealingEvent>): ByteArray {
        require(events.size <= MAX_EVENTS) { "Self-healing event log too large" }
        return ByteArrayOutputStream().let { output ->
            DataOutputStream(output).use { stream ->
                stream.writeInt(MAGIC)
                stream.writeInt(VERSION)
                stream.writeInt(events.size)
                events.forEach { writeEvent(stream, it) }
            }
            output.toByteArray()
        }.also { require(it.size <= MAX_PAYLOAD_BYTES) { "Self-healing payload too large" } }
    }

    fun decode(bytes: ByteArray): List<SelfHealingEvent> =
        decodePayload(bytes).also { events ->
            require(events.map { it.revision } == (1L..events.size.toLong()).toList()) {
                "SelfHealingEvent full-log revisions must be contiguous"
            }
        }

    /** Decodes exactly one immutable segment without rebasing its global revision. */
    fun decodeSegment(bytes: ByteArray): SelfHealingEvent =
        decodePayload(bytes).also { events ->
            require(events.size == 1) { "Self-healing segment must contain exactly one event" }
        }.single()

    private fun decodePayload(bytes: ByteArray): List<SelfHealingEvent> {
        require(bytes.isNotEmpty() && bytes.size <= MAX_PAYLOAD_BYTES) { "Invalid self-healing payload size" }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == MAGIC) { "Invalid self-healing payload magic" }
        require(input.readInt() == VERSION) { "Unsupported self-healing payload version" }
        val count = input.readInt()
        require(count in 0..MAX_EVENTS) { "Invalid self-healing event count" }
        val events = List(count) {
            readEvent(input)
        }
        require(input.available() == 0) { "Trailing self-healing payload bytes" }
        return events
    }

    private fun writeEvent(output: DataOutputStream, event: SelfHealingEvent) {
        output.writeLong(event.revision)
        writeString(output, event.incidentId.value)
        writeString(output, event.nodeId.value)
        writeString(output, event.planFingerprint)
        writeString(output, event.type.name)
        writeString(output, event.recordedAt.toString())
        output.writeBoolean(event.actionIndex != null)
        event.actionIndex?.let(output::writeInt)
        writeNullableString(output, event.actionId)
        writeNullableString(output, event.detail)
        writeNullableString(output, event.evidenceSummary)
    }

    private fun readEvent(input: DataInputStream): SelfHealingEvent = SelfHealingEvent(
        revision = input.readLong(),
        incidentId = SelfHealingIncidentId(readString(input)),
        nodeId = HealthNodeId(readString(input)),
        planFingerprint = readString(input),
        type = enumValueOf(readString(input)),
        recordedAt = Instant.parse(readString(input)),
        actionIndex = if (input.readBoolean()) input.readInt() else null,
        actionId = readNullableString(input),
        detail = readNullableString(input),
        evidenceSummary = readNullableString(input),
    )

    private fun writeNullableString(output: DataOutputStream, value: String?) {
        output.writeBoolean(value != null)
        if (value != null) writeString(output, value)
    }

    private fun readNullableString(input: DataInputStream): String? =
        if (input.readBoolean()) readString(input) else null

    private fun writeString(output: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "Self-healing string too large" }
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val length = input.readInt()
        require(length in 0..MAX_STRING_BYTES && length <= input.available()) {
            "Invalid self-healing string length"
        }
        return ByteArray(length).also(input::readFully).toString(Charsets.UTF_8)
    }
}
