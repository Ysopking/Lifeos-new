package app.lifeos.core.runtime.policy

import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.resource.ResourceBudgetAccountId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant

/** Deterministic bounded codec for the durable append-only owner-policy event log. */
object OwnerPolicyEventLogCodec {
    private const val MAGIC = 0x4f504c31 // OPL1
    private const val VERSION = 1
    private const val MAX_EVENTS = 20_000
    private const val MAX_STRING_BYTES = 32 * 1024
    const val MAX_PAYLOAD_BYTES = 8 * 1024 * 1024

    fun encode(events: List<OwnerPolicyEvent>): ByteArray {
        require(events.map { it.revision } == (1L..events.size.toLong()).toList()) {
            "Owner policy event revisions must be contiguous"
        }
        return encodePayload(events)
    }

    /**
     * Encodes one immutable segmented-ledger event without pretending its global revision starts at
     * one. The full-log codec above remains strict so legacy/migration payloads cannot hide gaps.
     */
    fun encodeSegment(event: OwnerPolicyEvent): ByteArray = encodePayload(listOf(event))

    fun decode(bytes: ByteArray): List<OwnerPolicyEvent> =
        decodePayload(bytes).also { events ->
            require(events.map { it.revision } == (1L..events.size.toLong()).toList()) {
                "Owner policy event revisions must be contiguous"
            }
        }

    /** Decodes exactly one segmented-ledger event while preserving its global revision. */
    fun decodeSegment(bytes: ByteArray): OwnerPolicyEvent =
        decodePayload(bytes).also { events ->
            require(events.size == 1) { "Owner policy segment must contain exactly one event" }
        }.single()

    private fun encodePayload(events: List<OwnerPolicyEvent>): ByteArray {
        require(events.size <= MAX_EVENTS) { "Owner policy event log too large" }
        return ByteArrayOutputStream().let { output ->
            DataOutputStream(output).use { stream ->
                stream.writeInt(MAGIC)
                stream.writeInt(VERSION)
                stream.writeInt(events.size)
                events.forEach { event -> writeEvent(stream, event) }
            }
            output.toByteArray()
        }.also { require(it.size <= MAX_PAYLOAD_BYTES) { "Owner policy payload too large" } }
    }

    private fun decodePayload(bytes: ByteArray): List<OwnerPolicyEvent> {
        require(bytes.isNotEmpty() && bytes.size <= MAX_PAYLOAD_BYTES) { "Invalid owner policy payload size" }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == MAGIC) { "Invalid owner policy payload magic" }
        require(input.readInt() == VERSION) { "Unsupported owner policy payload version" }
        val count = input.readInt()
        require(count in 0..MAX_EVENTS) { "Invalid owner policy event count" }
        val events = List(count) { readEvent(input) }
        require(input.available() == 0) { "Trailing owner policy payload bytes" }
        return events
    }

    private fun writeEvent(output: DataOutputStream, event: OwnerPolicyEvent) {
        output.writeLong(event.revision)
        writeString(output, event.type.name)
        writeString(output, event.recordedAt.toString())
        when (event.type) {
            OwnerPolicyEventType.GRANT -> writeGrant(output, requireNotNull(event.grant))
            OwnerPolicyEventType.REVOKE -> writeString(output, requireNotNull(event.revokedGrantId).value)
        }
    }

    private fun readEvent(input: DataInputStream): OwnerPolicyEvent {
        val revision = input.readLong()
        val type = enumValueOf<OwnerPolicyEventType>(readString(input))
        val recordedAt = Instant.parse(readString(input))
        return when (type) {
            OwnerPolicyEventType.GRANT -> OwnerPolicyEvent(
                revision = revision,
                type = type,
                recordedAt = recordedAt,
                grant = readGrant(input),
            )
            OwnerPolicyEventType.REVOKE -> OwnerPolicyEvent(
                revision = revision,
                type = type,
                recordedAt = recordedAt,
                revokedGrantId = OwnerPolicyGrantId(readString(input)),
            )
        }
    }

    private fun writeGrant(output: DataOutputStream, grant: OwnerPolicyGrant) {
        writeString(output, grant.id.value)
        writeString(output, grant.actorId.value)
        writeString(output, grant.effect.name)
        writeString(output, grant.resource.type.name)
        writeNullableString(output, grant.resource.value)
        writeString(output, grant.scope)
        output.writeBoolean(grant.capability != null)
        grant.capability?.let { capability ->
            writeString(output, capability.capabilityId.value)
            writeNullableString(output, capability.providerVersion)
        }
        output.writeBoolean(grant.budgetAccountId != null)
        grant.budgetAccountId?.let { writeString(output, it.value) }
        writeString(output, grant.validFrom.toString())
        writeNullableString(output, grant.validUntil?.toString())
    }

    private fun readGrant(input: DataInputStream): OwnerPolicyGrant {
        val id = OwnerPolicyGrantId(readString(input))
        val actorId = OwnerActorId(readString(input))
        val effect = enumValueOf<OwnerEffectType>(readString(input))
        val resourceType = enumValueOf<OwnerResourceSelectorType>(readString(input))
        val resource = OwnerResourceSelector(resourceType, readNullableString(input))
        val scope = readString(input)
        val capability = if (input.readBoolean()) {
            OwnerCapabilityConstraint(
                capabilityId = CapabilityId(readString(input)),
                providerVersion = readNullableString(input),
            )
        } else null
        val budget = if (input.readBoolean()) ResourceBudgetAccountId(readString(input)) else null
        val validFrom = Instant.parse(readString(input))
        val validUntil = readNullableString(input)?.let(Instant::parse)
        return OwnerPolicyGrant(
            id = id,
            actorId = actorId,
            effect = effect,
            resource = resource,
            scope = scope,
            capability = capability,
            budgetAccountId = budget,
            validFrom = validFrom,
            validUntil = validUntil,
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
        require(bytes.size <= MAX_STRING_BYTES) { "Owner policy string too large" }
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val length = input.readInt()
        require(length in 0..MAX_STRING_BYTES && length <= input.available()) { "Invalid owner policy string length" }
        return ByteArray(length).also(input::readFully).toString(Charsets.UTF_8)
    }
}
