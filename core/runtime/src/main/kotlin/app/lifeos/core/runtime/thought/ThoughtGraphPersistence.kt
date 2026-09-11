package app.lifeos.core.runtime.thought

import app.lifeos.core.field.TemporalValidity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant

data class ThoughtGraphDeltaLoadReport(
    val deltas: List<ThoughtGraphDelta>,
    val unreadableEntries: List<String>,
) {
    init {
        require(deltas == deltas.distinctBy { it.id }.sortedBy { it.id.value }) {
            "Thought graph deltas must be unique and deterministically ordered"
        }
        require(unreadableEntries == unreadableEntries.distinct().sorted()) {
            "Unreadable thought graph entries must be unique and deterministically ordered"
        }
    }
}

sealed interface ThoughtGraphDeltaWriteResult {
    val delta: ThoughtGraphDelta

    data class Stored(override val delta: ThoughtGraphDelta) : ThoughtGraphDeltaWriteResult
    data class Duplicate(override val delta: ThoughtGraphDelta) : ThoughtGraphDeltaWriteResult
}

interface ThoughtGraphDeltaRepository {
    suspend fun save(delta: ThoughtGraphDelta): ThoughtGraphDeltaWriteResult
    suspend fun load(id: ThoughtGraphDeltaId): ThoughtGraphDelta?
    suspend fun loadReport(): ThoughtGraphDeltaLoadReport
}

/** Canonical bounded binary codec for one immutable append-only thought-graph delta. */
object ThoughtGraphDeltaCodec {
    const val MAX_PAYLOAD_BYTES: Int = 32 * 1024 * 1024
    private const val VERSION = 1
    private const val MAX_VERSIONS = 100_000
    private const val MAX_ATTRIBUTES = 4096
    private const val MAX_STRING_BYTES = 256 * 1024

    fun encode(delta: ThoughtGraphDelta): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { out ->
            out.writeInt(VERSION)
            writeString(out, delta.id.value)
            writeString(out, delta.sourceKey)
            out.writeLong(delta.sourceRevision)
            writeInstant(out, delta.observedAt)
            out.writeInt(delta.nodeVersions.size)
            delta.nodeVersions.forEach { writeNode(out, it) }
            out.writeInt(delta.edgeVersions.size)
            delta.edgeVersions.forEach { writeEdge(out, it) }
        }
        return output.toByteArray().also {
            require(it.isNotEmpty() && it.size <= MAX_PAYLOAD_BYTES) {
                "Thought graph delta payload too large"
            }
        }
    }

    fun decode(payload: ByteArray): ThoughtGraphDelta {
        require(payload.isNotEmpty() && payload.size <= MAX_PAYLOAD_BYTES) {
            "Invalid thought graph delta payload size"
        }
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == VERSION) { "Unsupported thought graph delta version" }
            val id = ThoughtGraphDeltaId(readString(input))
            val sourceKey = readString(input)
            val sourceRevision = input.readLong()
            val observedAt = readInstant(input)
            val nodes = List(readCount(input, MAX_VERSIONS, "node versions")) { readNode(input) }
            val edges = List(readCount(input, MAX_VERSIONS, "edge versions")) { readEdge(input) }
            require(input.available() == 0) { "Trailing thought graph delta data" }
            ThoughtGraphDelta(
                id = id,
                sourceKey = sourceKey,
                sourceRevision = sourceRevision,
                nodeVersions = nodes,
                edgeVersions = edges,
                observedAt = observedAt,
            )
        }
    }

    private fun writeNode(out: DataOutputStream, node: ThoughtGraphNodeVersion) {
        writeString(out, node.id.value)
        writeString(out, node.kind.name)
        writeString(out, node.semanticKey)
        writeString(out, node.summary)
        out.writeDouble(node.confidence)
        out.writeDouble(node.authority)
        writeValidity(out, node.validity)
        writeProvenance(out, node.provenance)
        val attributes = node.attributes.toSortedMap()
        require(attributes.size <= MAX_ATTRIBUTES) { "Too many thought graph node attributes" }
        out.writeInt(attributes.size)
        attributes.forEach { (key, value) ->
            writeString(out, key)
            writeString(out, value)
        }
    }

    private fun readNode(input: DataInputStream): ThoughtGraphNodeVersion {
        val id = ThoughtGraphNodeId(readString(input))
        val kind = enumValueOf<ThoughtGraphNodeKind>(readString(input))
        val semanticKey = readString(input)
        val summary = readString(input)
        val confidence = input.readDouble()
        val authority = input.readDouble()
        val validity = readValidity(input)
        val provenance = readProvenance(input)
        val attributePairs = List(readCount(input, MAX_ATTRIBUTES, "node attributes")) {
            readString(input) to readString(input)
        }
        require(attributePairs.map { it.first }.distinct().size == attributePairs.size) {
            "Duplicate thought graph node attribute keys"
        }
        return ThoughtGraphNodeVersion(
            id = id,
            kind = kind,
            semanticKey = semanticKey,
            summary = summary,
            confidence = confidence,
            authority = authority,
            validity = validity,
            provenance = provenance,
            attributes = attributePairs.toMap().toSortedMap(),
        )
    }

    private fun writeEdge(out: DataOutputStream, edge: ThoughtGraphEdgeVersion) {
        writeString(out, edge.id.value)
        writeString(out, edge.sourceNodeId.value)
        writeString(out, edge.targetNodeId.value)
        writeString(out, edge.kind.name)
        writeString(out, edge.semanticKey)
        out.writeDouble(edge.confidence)
        out.writeDouble(edge.authority)
        writeValidity(out, edge.validity)
        writeProvenance(out, edge.provenance)
        writeString(out, edge.explanation)
    }

    private fun readEdge(input: DataInputStream): ThoughtGraphEdgeVersion = ThoughtGraphEdgeVersion(
        id = ThoughtGraphEdgeId(readString(input)),
        sourceNodeId = ThoughtGraphNodeId(readString(input)),
        targetNodeId = ThoughtGraphNodeId(readString(input)),
        kind = enumValueOf(readString(input)),
        semanticKey = readString(input),
        confidence = input.readDouble(),
        authority = input.readDouble(),
        validity = readValidity(input),
        provenance = readProvenance(input),
        explanation = readString(input),
    )

    private fun writeProvenance(out: DataOutputStream, provenance: ThoughtGraphProvenance) {
        writeString(out, provenance.sourceKind.name)
        writeString(out, provenance.sourceId)
        out.writeLong(provenance.sourceRevision)
        writeString(out, provenance.sourceFingerprint)
        writeString(out, provenance.origin)
        writeString(out, provenance.actor)
        writeInstant(out, provenance.createdAt)
    }

    private fun readProvenance(input: DataInputStream): ThoughtGraphProvenance = ThoughtGraphProvenance(
        sourceKind = enumValueOf(readString(input)),
        sourceId = readString(input),
        sourceRevision = input.readLong(),
        sourceFingerprint = readString(input),
        origin = readString(input),
        actor = readString(input),
        createdAt = readInstant(input),
    )

    private fun writeValidity(out: DataOutputStream, validity: TemporalValidity) {
        writeNullableInstant(out, validity.validFrom)
        writeNullableInstant(out, validity.validUntilExclusive)
    }

    private fun readValidity(input: DataInputStream): TemporalValidity = TemporalValidity(
        validFrom = readNullableInstant(input),
        validUntilExclusive = readNullableInstant(input),
    )

    private fun writeInstant(out: DataOutputStream, instant: Instant) {
        out.writeLong(instant.epochSecond)
        out.writeInt(instant.nano)
    }

    private fun readInstant(input: DataInputStream): Instant =
        Instant.ofEpochSecond(input.readLong(), input.readInt().toLong())

    private fun writeNullableInstant(out: DataOutputStream, instant: Instant?) {
        out.writeBoolean(instant != null)
        if (instant != null) writeInstant(out, instant)
    }

    private fun readNullableInstant(input: DataInputStream): Instant? =
        if (input.readBoolean()) readInstant(input) else null

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "Thought graph string too large" }
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val length = input.readInt()
        require(length in 0..MAX_STRING_BYTES && length <= input.available()) {
            "Invalid thought graph string length"
        }
        return ByteArray(length).also(input::readFully).toString(StandardCharsets.UTF_8)
    }

    private fun readCount(input: DataInputStream, max: Int, label: String): Int =
        input.readInt().also { require(it in 0..max) { "Invalid thought graph $label count" } }
}
