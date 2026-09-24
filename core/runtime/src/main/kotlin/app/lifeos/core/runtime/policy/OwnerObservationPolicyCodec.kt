package app.lifeos.core.runtime.policy

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant

/** Deterministic bounded codec for the append-only Owner Observation Policy event log. */
object OwnerObservationPolicyEventLogCodec {
    private const val MAGIC = 0x4f4f5031 // OOP1
    private const val VERSION = 1
    private const val MAX_EVENTS = 20_000
    private const val MAX_STRING_BYTES = 32 * 1024
    const val MAX_PAYLOAD_BYTES = 8 * 1024 * 1024

    fun encode(events: List<OwnerObservationPolicyEvent>): ByteArray {
        require(events.map { it.revision } == (1L..events.size.toLong()).toList()) {
            "Owner observation policy revisions must be contiguous"
        }
        return encodePayload(events)
    }

    fun encodeSegment(event: OwnerObservationPolicyEvent): ByteArray =
        encodePayload(listOf(event))

    fun decode(bytes: ByteArray): List<OwnerObservationPolicyEvent> =
        decodePayload(bytes).also { events ->
            require(events.map { it.revision } == (1L..events.size.toLong()).toList()) {
                "Owner observation policy revisions must be contiguous"
            }
        }

    fun decodeSegment(bytes: ByteArray): OwnerObservationPolicyEvent =
        decodePayload(bytes).also { events ->
            require(events.size == 1) {
                "Owner observation policy segment must contain exactly one event"
            }
        }.single()

    private fun encodePayload(events: List<OwnerObservationPolicyEvent>): ByteArray {
        require(events.size <= MAX_EVENTS)
        return ByteArrayOutputStream().let { output ->
            DataOutputStream(output).use { stream ->
                stream.writeInt(MAGIC)
                stream.writeInt(VERSION)
                stream.writeInt(events.size)
                events.forEach { event -> writeEvent(stream, event) }
            }
            output.toByteArray()
        }.also {
            require(it.size <= MAX_PAYLOAD_BYTES) {
                "Owner observation policy payload too large"
            }
        }
    }

    private fun decodePayload(bytes: ByteArray): List<OwnerObservationPolicyEvent> {
        require(bytes.isNotEmpty() && bytes.size <= MAX_PAYLOAD_BYTES)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == MAGIC) {
            "Invalid owner observation policy payload magic"
        }
        require(input.readInt() == VERSION) {
            "Unsupported owner observation policy payload version"
        }
        val count = input.readInt()
        require(count in 0..MAX_EVENTS)
        val events = List(count) { readEvent(input) }
        require(input.available() == 0) {
            "Trailing owner observation policy payload bytes"
        }
        return events
    }

    private fun writeEvent(
        output: DataOutputStream,
        event: OwnerObservationPolicyEvent,
    ) {
        output.writeLong(event.revision)
        writeString(output, event.type.name)
        writeString(output, event.recordedAt.toString())
        when (event.type) {
            OwnerObservationPolicyEventType.GRANT ->
                writeGrant(output, requireNotNull(event.grant))
            OwnerObservationPolicyEventType.REVOKE ->
                writeString(output, requireNotNull(event.revokedGrantId).value)
        }
    }

    private fun readEvent(input: DataInputStream): OwnerObservationPolicyEvent {
        val revision = input.readLong()
        val type = enumValueOf<OwnerObservationPolicyEventType>(readString(input))
        val recordedAt = Instant.parse(readString(input))
        return when (type) {
            OwnerObservationPolicyEventType.GRANT ->
                OwnerObservationPolicyEvent(
                    revision = revision,
                    type = type,
                    recordedAt = recordedAt,
                    grant = readGrant(input),
                )
            OwnerObservationPolicyEventType.REVOKE ->
                OwnerObservationPolicyEvent(
                    revision = revision,
                    type = type,
                    recordedAt = recordedAt,
                    revokedGrantId = OwnerObservationGrantId(readString(input)),
                )
        }
    }

    private fun writeGrant(
        output: DataOutputStream,
        grant: OwnerObservationGrant,
    ) {
        writeString(output, grant.id.value)
        writeString(output, grant.actorId.value)
        writeString(output, grant.observationType.name)
        writeString(output, grant.resource.type.name)
        writeNullableString(output, grant.resource.value)
        writeString(output, grant.scope)
        writeNullableString(output, grant.sensorId)
        writeString(output, grant.validFrom.toString())
        writeNullableString(output, grant.validUntil?.toString())
    }

    private fun readGrant(input: DataInputStream): OwnerObservationGrant =
        OwnerObservationGrant(
            id = OwnerObservationGrantId(readString(input)),
            actorId = OwnerActorId(readString(input)),
            observationType = enumValueOf<OwnerObservationType>(readString(input)),
            resource = OwnerResourceSelector(
                enumValueOf<OwnerResourceSelectorType>(readString(input)),
                readNullableString(input),
            ),
            scope = readString(input),
            sensorId = readNullableString(input),
            validFrom = Instant.parse(readString(input)),
            validUntil = readNullableString(input)?.let(Instant::parse),
        )

    private fun writeNullableString(output: DataOutputStream, value: String?) {
        output.writeBoolean(value != null)
        if (value != null) writeString(output, value)
    }

    private fun readNullableString(input: DataInputStream): String? =
        if (input.readBoolean()) readString(input) else null

    private fun writeString(output: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES)
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val length = input.readInt()
        require(length in 0..MAX_STRING_BYTES && length <= input.available())
        return ByteArray(length).also(input::readFully).toString(Charsets.UTF_8)
    }
}
