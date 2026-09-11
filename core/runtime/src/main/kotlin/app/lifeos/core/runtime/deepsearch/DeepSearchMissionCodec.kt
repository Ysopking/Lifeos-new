package app.lifeos.core.runtime.deepsearch

import app.lifeos.core.model.PhotonId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant

object DeepSearchMissionEventLogCodec {
    private const val MAGIC = 0x44534D32 // DSM2
    private const val VERSION = 1
    private const val MAX_EVENTS = 50_000
    private const val MAX_STRING_BYTES = 128 * 1024
    const val MAX_PAYLOAD_BYTES = 16 * 1024 * 1024

    fun encode(events: List<DeepSearchMissionEvent>): ByteArray {
        require(events.size <= MAX_EVENTS)
        require(events.map { it.revision } == (1L..events.size.toLong()).toList())
        return ByteArrayOutputStream().let { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeInt(events.size)
                events.forEach { event ->
                    out.writeLong(event.revision)
                    write(out, event.missionId.value)
                    write(out, event.type.name)
                    write(out, event.recordedAt.toString())
                    out.writeBoolean(event.definition != null)
                    event.definition?.let { definition ->
                        write(out, definition.goalPhotonId.value)
                        write(out, definition.sourcePhotonId.value)
                        out.writeLong(definition.sourceRevision)
                        write(out, definition.query)
                        write(out, definition.searchPolicyVersion)
                        out.writeInt(definition.sourceScopeIds.size)
                        definition.sourceScopeIds.sorted().forEach { write(out, it) }
                        write(out, definition.createdAt.toString())
                    }
                    writeNullable(out, event.checkpointFingerprint)
                    writeNullable(out, event.resultPhotonId?.value)
                    writeNullable(out, event.detail)
                }
            }
            bytes.toByteArray()
        }.also { require(it.size <= MAX_PAYLOAD_BYTES) }
    }

    fun decode(bytes: ByteArray): List<DeepSearchMissionEvent> {
        require(bytes.isNotEmpty() && bytes.size <= MAX_PAYLOAD_BYTES)
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == MAGIC) { "Invalid DeepSearch mission payload magic" }
        require(input.readInt() == VERSION) { "Unsupported DeepSearch mission payload version" }
        val count = input.readInt()
        require(count in 0..MAX_EVENTS)
        val events = List(count) { index ->
            val revision = input.readLong()
            val missionId = DeepSearchMissionId(read(input))
            val type = enumValueOf<DeepSearchMissionEventType>(read(input))
            val recordedAt = Instant.parse(read(input))
            val definition = if (input.readBoolean()) {
                val goalPhotonId = PhotonId(read(input))
                val sourcePhotonId = PhotonId(read(input))
                val sourceRevision = input.readLong()
                val query = read(input)
                val policy = read(input)
                val scopeCount = input.readInt()
                require(scopeCount in 1..256)
                val scopes = List(scopeCount) { read(input) }.toSet()
                require(scopes.size == scopeCount) { "Duplicate DeepSearch source scope" }
                DeepSearchMissionDefinition(
                    id = missionId,
                    goalPhotonId = goalPhotonId,
                    sourcePhotonId = sourcePhotonId,
                    sourceRevision = sourceRevision,
                    query = query,
                    searchPolicyVersion = policy,
                    sourceScopeIds = scopes,
                    createdAt = Instant.parse(read(input)),
                )
            } else null
            DeepSearchMissionEvent(
                revision = revision,
                missionId = missionId,
                type = type,
                recordedAt = recordedAt,
                definition = definition,
                checkpointFingerprint = readNullable(input),
                resultPhotonId = readNullable(input)?.let(::PhotonId),
                detail = readNullable(input),
            ).also { require(it.revision == index + 1L) }
        }
        require(input.available() == 0) { "Trailing DeepSearch mission payload bytes" }
        return events
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
