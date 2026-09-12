package app.lifeos.core.runtime.trace

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant

/** Deterministic bounded codec for durable V15 decision-trace revisions. */
object DecisionTraceLogCodec {
    private const val MAGIC = 0x44544c31 // DTL1
    private const val VERSION = 1
    private const val MAX_TRACE_REVISIONS = 10_000
    private const val MAX_NODES_PER_TRACE = 2_000
    private const val MAX_LINKS_PER_TRACE = 8_000
    private const val MAX_REASON_CODES_PER_NODE = 64
    private const val MAX_STRING_BYTES = 32 * 1024
    const val MAX_PAYLOAD_BYTES = 16 * 1024 * 1024

    fun encode(traces: List<DecisionTrace>): ByteArray {
        require(traces.size <= MAX_TRACE_REVISIONS) { "Decision trace log too large" }
        val canonical = canonicalize(traces)
        validateRevisionChains(canonical)
        return ByteArrayOutputStream().let { output ->
            DataOutputStream(output).use { stream ->
                stream.writeInt(MAGIC)
                stream.writeInt(VERSION)
                stream.writeInt(canonical.size)
                canonical.forEach { trace -> writeTrace(stream, trace) }
            }
            output.toByteArray()
        }.also { bytes ->
            require(bytes.isNotEmpty() && bytes.size <= MAX_PAYLOAD_BYTES) {
                "Decision trace payload too large"
            }
        }
    }

    fun decode(bytes: ByteArray): List<DecisionTrace> {
        require(bytes.isNotEmpty() && bytes.size <= MAX_PAYLOAD_BYTES) {
            "Invalid decision trace payload size"
        }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == MAGIC) { "Invalid decision trace payload magic" }
        require(input.readInt() == VERSION) { "Unsupported decision trace payload version" }
        val count = input.readInt()
        require(count in 0..MAX_TRACE_REVISIONS) { "Invalid decision trace revision count" }
        val traces = List(count) { readTrace(input) }
        require(input.available() == 0) { "Trailing decision trace payload bytes" }
        val canonical = canonicalize(traces)
        require(traces == canonical) { "Decision trace payload is not canonically ordered" }
        validateRevisionChains(canonical)
        return canonical
    }

    private fun canonicalize(traces: List<DecisionTrace>): List<DecisionTrace> =
        traces.sortedWith(compareBy<DecisionTrace>({ it.id.value }, { it.revision }))

    private fun validateRevisionChains(traces: List<DecisionTrace>) {
        traces.groupBy { it.id }.forEach { (_, revisions) ->
            require(revisions.map { it.revision } == (1L..revisions.size.toLong()).toList()) {
                "Decision trace revisions must be contiguous"
            }
        }
    }

    private fun writeTrace(output: DataOutputStream, trace: DecisionTrace) {
        require(trace.revision >= 1L) { "Persisted decision trace revision must be positive" }
        require(trace.nodes.size <= MAX_NODES_PER_TRACE) { "Decision trace has too many nodes" }
        require(trace.links.size <= MAX_LINKS_PER_TRACE) { "Decision trace has too many links" }
        writeString(output, trace.id.value)
        output.writeLong(trace.revision)

        val nodes = trace.nodes.sortedBy { it.id.value }
        output.writeInt(nodes.size)
        nodes.forEach { node -> writeNode(output, node) }

        val links = trace.links.sortedWith(compareBy({ it.from.value }, { it.to.value }, { it.type.name }))
        output.writeInt(links.size)
        links.forEach { link ->
            writeString(output, link.from.value)
            writeString(output, link.to.value)
            writeString(output, link.type.name)
        }
    }

    private fun readTrace(input: DataInputStream): DecisionTrace {
        val id = DecisionTraceId(readString(input))
        val revision = input.readLong()
        require(revision >= 1L) { "Invalid persisted decision trace revision" }

        val nodeCount = input.readInt()
        require(nodeCount in 0..MAX_NODES_PER_TRACE) { "Invalid decision trace node count" }
        val nodes = List(nodeCount) { readNode(input) }
        require(nodes == nodes.sortedBy { it.id.value }) { "Decision trace nodes are not canonically ordered" }

        val linkCount = input.readInt()
        require(linkCount in 0..MAX_LINKS_PER_TRACE) { "Invalid decision trace link count" }
        val links = List(linkCount) {
            DecisionTraceLink(
                from = DecisionTraceNodeId(readString(input)),
                to = DecisionTraceNodeId(readString(input)),
                type = enumValueOf(readString(input)),
            )
        }
        val sortedLinks = links.sortedWith(compareBy({ it.from.value }, { it.to.value }, { it.type.name }))
        require(links == sortedLinks) { "Decision trace links are not canonically ordered" }
        return DecisionTrace(id, revision, nodes, links)
    }

    private fun writeNode(output: DataOutputStream, node: DecisionTraceNode) {
        require(node.reasonCodes.size <= MAX_REASON_CODES_PER_NODE) { "Too many decision trace reason codes" }
        writeString(output, node.id.value)
        writeString(output, node.type.name)
        writeString(output, node.sourceType)
        writeString(output, node.sourceId)
        output.writeLong(node.sourceRevision)
        output.writeInt(node.reasonCodes.size)
        node.reasonCodes.forEach { reason -> writeString(output, reason) }
        writeNullableString(output, node.displayLabel)
        writeString(output, node.recordedAt.toString())
    }

    private fun readNode(input: DataInputStream): DecisionTraceNode {
        val id = DecisionTraceNodeId(readString(input))
        val type = enumValueOf<DecisionTraceNodeType>(readString(input))
        val sourceType = readString(input)
        val sourceId = readString(input)
        val sourceRevision = input.readLong()
        val reasonCount = input.readInt()
        require(reasonCount in 0..MAX_REASON_CODES_PER_NODE) { "Invalid decision trace reason count" }
        val reasons = List(reasonCount) { readString(input) }
        require(reasons == reasons.distinct().sorted()) { "Decision trace reason codes are not canonical" }
        return DecisionTraceNode(
            id = id,
            type = type,
            sourceType = sourceType,
            sourceId = sourceId,
            sourceRevision = sourceRevision,
            reasonCodes = reasons,
            displayLabel = readNullableString(input),
            recordedAt = Instant.parse(readString(input)),
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
        require(bytes.size <= MAX_STRING_BYTES) { "Decision trace string too large" }
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val length = input.readInt()
        require(length in 0..MAX_STRING_BYTES && length <= input.available()) {
            "Invalid decision trace string length"
        }
        return ByteArray(length).also(input::readFully).toString(Charsets.UTF_8)
    }
}
