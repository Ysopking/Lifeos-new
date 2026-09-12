package app.lifeos.core.runtime.capability

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant

object ToolWorkshopJobEventLogCodec {
    private const val MAGIC = 0x54574A31 // TWJ1
    private const val VERSION = 1
    private const val MAX_EVENTS = 40_000
    private const val MAX_COLLECTION = 512
    private const val MAX_STRING_BYTES = 64 * 1024
    const val MAX_PAYLOAD_BYTES = 16 * 1024 * 1024

    fun encode(events: List<ToolWorkshopJobEvent>): ByteArray {
        require(events.size <= MAX_EVENTS)
        require(events.map { it.revision } == (1L..events.size.toLong()).toList())
        return ByteArrayOutputStream().let { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeInt(events.size)
                events.forEach { event ->
                    out.writeLong(event.revision)
                    writeDefinition(out, event.definition)
                    write(out, event.state.name)
                    write(out, event.recordedAt.toString())
                    writeNullable(out, event.stageFingerprint)
                    writeNullable(out, event.detail)
                }
            }
            bytes.toByteArray()
        }.also { require(it.size <= MAX_PAYLOAD_BYTES) }
    }

    fun decode(bytes: ByteArray): List<ToolWorkshopJobEvent> {
        require(bytes.isNotEmpty() && bytes.size <= MAX_PAYLOAD_BYTES)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == MAGIC) { "Invalid ToolWorkshop job payload magic" }
        require(input.readInt() == VERSION) { "Unsupported ToolWorkshop job payload version" }
        val count = input.readInt()
        require(count in 0..MAX_EVENTS)
        val events = List(count) { index ->
            ToolWorkshopJobEvent(
                revision = input.readLong(),
                definition = readDefinition(input),
                state = enumValueOf(read(input)),
                recordedAt = Instant.parse(read(input)),
                stageFingerprint = readNullable(input),
                detail = readNullable(input),
            ).also { require(it.revision == index + 1L) }
        }
        require(input.available() == 0) { "Trailing ToolWorkshop job payload bytes" }
        return events
    }

    private fun writeDefinition(out: DataOutputStream, value: ToolWorkshopJobDefinition) {
        write(out, value.id.value)
        write(out, value.sourceRequestId)
        write(out, value.sourcePhotonId)
        out.writeLong(value.sourceRevision)
        write(out, value.capabilityId.value)
        write(out, value.severity.name)
        write(out, value.gapType.name)
        writeSet(out, value.requiredInputs)
        writeSet(out, value.requiredOutputs)
        writeList(out, value.candidateProviderIds)
        write(out, value.policyVersion)
        write(out, value.workshopVersion)
        write(out, value.createdAt.toString())
    }

    private fun readDefinition(input: DataInputStream): ToolWorkshopJobDefinition = ToolWorkshopJobDefinition(
        id = ToolWorkshopJobId(read(input)),
        sourceRequestId = read(input),
        sourcePhotonId = read(input),
        sourceRevision = input.readLong(),
        capabilityId = CapabilityId(read(input)),
        severity = enumValueOf(read(input)),
        gapType = enumValueOf(read(input)),
        requiredInputs = readList(input).toSet(),
        requiredOutputs = readList(input).toSet(),
        candidateProviderIds = readList(input),
        policyVersion = read(input),
        workshopVersion = read(input),
        createdAt = Instant.parse(read(input)),
    )

    private fun writeSet(out: DataOutputStream, values: Set<String>) = writeList(out, values.sorted())

    private fun writeList(out: DataOutputStream, values: List<String>) {
        require(values.size <= MAX_COLLECTION)
        out.writeInt(values.size)
        values.forEach { write(out, it) }
    }

    private fun readList(input: DataInputStream): List<String> {
        val count = input.readInt()
        require(count in 0..MAX_COLLECTION)
        return List(count) { read(input) }
    }

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
