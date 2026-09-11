package app.lifeos.core.runtime.thought

import app.lifeos.core.field.StableFieldIds
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

@JvmInline
value class ThoughtGraphDeltaSegmentId(val value: String) {
    init { require(value.isNotBlank()) { "Thought graph segment id must not be blank" } }
}

data class ThoughtGraphDeltaSegment(
    val id: ThoughtGraphDeltaSegmentId,
    val deltas: List<ThoughtGraphDelta>,
) {
    init {
        require(deltas.size in 2..ThoughtGraphDeltaSegmentCodec.MAX_DELTAS) {
            "Thought graph segment must contain 2..${ThoughtGraphDeltaSegmentCodec.MAX_DELTAS} deltas"
        }
        require(deltas == deltas.distinctBy { it.id }.sortedBy { it.id.value }) {
            "Thought graph segment deltas must be unique and deterministically ordered"
        }
        require(id == expectedId(deltas)) { "Thought graph segment id must match exact content" }
    }

    companion object {
        fun create(deltas: Iterable<ThoughtGraphDelta>): ThoughtGraphDeltaSegment {
            val stable = deltas.distinctBy { it.id }.sortedBy { it.id.value }
            return ThoughtGraphDeltaSegment(expectedId(stable), stable)
        }

        private fun expectedId(deltas: List<ThoughtGraphDelta>): ThoughtGraphDeltaSegmentId =
            ThoughtGraphDeltaSegmentId(
                SEGMENT_PREFIX + StableFieldIds.fingerprint(
                    "thought-graph/segment/v1",
                    *deltas.flatMap { delta ->
                        listOf(delta.id.value, delta.observedAt.toString())
                    }.toTypedArray(),
                )
            )

        const val SEGMENT_PREFIX = "thought-graph-segment:"
    }
}

/** Canonical bounded container that preserves each immutable delta byte-for-byte at model level. */
object ThoughtGraphDeltaSegmentCodec {
    const val MAX_DELTAS: Int = 512
    const val MAX_PAYLOAD_BYTES: Int = 64 * 1024 * 1024
    private const val VERSION = 1
    private const val MAX_STRING_BYTES = 256 * 1024

    fun encode(segment: ThoughtGraphDeltaSegment): ByteArray {
        val encodedDeltas = segment.deltas.map(ThoughtGraphDeltaCodec::encode)
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { out ->
            out.writeInt(VERSION)
            writeString(out, segment.id.value)
            out.writeInt(encodedDeltas.size)
            encodedDeltas.forEach { payload ->
                out.writeInt(payload.size)
                out.write(payload)
            }
        }
        return output.toByteArray().also {
            require(it.isNotEmpty() && it.size <= MAX_PAYLOAD_BYTES) {
                "Thought graph segment payload too large"
            }
        }
    }

    fun decode(payload: ByteArray): ThoughtGraphDeltaSegment {
        require(payload.isNotEmpty() && payload.size <= MAX_PAYLOAD_BYTES) {
            "Invalid thought graph segment payload size"
        }
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == VERSION) { "Unsupported thought graph segment version" }
            val id = ThoughtGraphDeltaSegmentId(readString(input))
            val count = input.readInt()
            require(count in 2..MAX_DELTAS) { "Invalid thought graph segment delta count" }
            val deltas = List(count) {
                val length = input.readInt()
                require(length in 1..ThoughtGraphDeltaCodec.MAX_PAYLOAD_BYTES && length <= input.available()) {
                    "Invalid thought graph segment delta length"
                }
                ThoughtGraphDeltaCodec.decode(ByteArray(length).also(input::readFully))
            }
            require(input.available() == 0) { "Trailing thought graph segment data" }
            ThoughtGraphDeltaSegment(id = id, deltas = deltas)
        }
    }

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "Thought graph segment string too large" }
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val length = input.readInt()
        require(length in 0..MAX_STRING_BYTES && length <= input.available()) {
            "Invalid thought graph segment string length"
        }
        return ByteArray(length).also(input::readFully).toString(StandardCharsets.UTF_8)
    }
}

data class ThoughtGraphCompactionPolicy(
    val triggerLooseDeltaCount: Int = 128,
    val retainLooseDeltaCount: Int = 32,
    val maxDeltasPerSegment: Int = 64,
) {
    init {
        require(triggerLooseDeltaCount >= 2) { "Compaction trigger must be at least 2" }
        require(retainLooseDeltaCount in 0 until triggerLooseDeltaCount) {
            "Compaction retain count must be smaller than trigger count"
        }
        require(maxDeltasPerSegment in 2..ThoughtGraphDeltaSegmentCodec.MAX_DELTAS) {
            "Compaction segment size out of bounds"
        }
    }
}

data class ThoughtGraphCompactionReport(
    val looseBefore: Int,
    val looseAfter: Int,
    val segmentsWritten: Int,
    val deltasCompacted: Int,
) {
    init {
        require(looseBefore >= 0 && looseAfter >= 0 && segmentsWritten >= 0 && deltasCompacted >= 0)
        require(looseAfter <= looseBefore) { "Compaction cannot increase loose delta count" }
    }
}

interface ThoughtGraphHistoryCompactor {
    suspend fun compact(
        policy: ThoughtGraphCompactionPolicy = ThoughtGraphCompactionPolicy(),
    ): ThoughtGraphCompactionReport
}
