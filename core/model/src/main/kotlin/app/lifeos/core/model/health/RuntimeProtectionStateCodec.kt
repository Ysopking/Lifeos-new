package app.lifeos.core.model.health

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant

object RuntimeProtectionStateCodec {
    const val VERSION = 1
    const val MAX_BYTES = 1024 * 1024
    private const val MAX_TEXT_BYTES = 64 * 1024

    fun encode(state: RuntimeProtectionState): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(VERSION)
            out.writeLong(state.generation)
            out.writeLong(state.revision)
            out.text(state.mode.name)
            out.writeInt(state.reasons.size)
            state.reasons.forEach { reason ->
                out.text(reason.code.name)
                out.text(reason.source)
                out.text(reason.message)
            }
            out.writeInt(state.affectedNodes.size)
            state.affectedNodes.sortedBy { it.value }.forEach { out.text(it.value) }
            out.optionalInstant(state.enteredAt)
            out.optionalInstant(state.lastVerifiedAt)
            out.text(state.actor.name)
            out.text(state.provenance)
            out.text(state.resumePolicy.name)
        }
        require(bytes.size() <= MAX_BYTES) { "Protection state exceeds size limit" }
    }.toByteArray()

    fun decode(bytes: ByteArray): RuntimeProtectionState {
        require(bytes.size <= MAX_BYTES) { "Protection state exceeds size limit" }
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val version = input.readInt()
            require(version == VERSION) { "Unsupported protection state version: $version" }
            val state = RuntimeProtectionState(
                generation = input.readLong(),
                revision = input.readLong(),
                mode = ProtectionMode.valueOf(input.text()),
                reasons = buildList {
                    repeat(input.count(RuntimeProtectionState.MAX_REASONS)) {
                        add(
                            ProtectionReason(
                                code = ProtectionReasonCode.valueOf(input.text()),
                                source = input.text(),
                                message = input.text(),
                            ),
                        )
                    }
                },
                affectedNodes = buildSet {
                    repeat(input.count(RuntimeProtectionState.MAX_AFFECTED_NODES)) {
                        check(add(ProtectionNodeRef(input.text()))) { "Duplicate protection node" }
                    }
                },
                enteredAt = input.optionalInstant(),
                lastVerifiedAt = input.optionalInstant(),
                actor = ProtectionActor.valueOf(input.text()),
                provenance = input.text(),
                resumePolicy = ProtectionResumePolicy.valueOf(input.text()),
            )
            require(input.available() == 0) { "Trailing protection state data" }
            state
        }
    }

    private fun DataOutputStream.text(value: String) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        require(encoded.size <= MAX_TEXT_BYTES) { "Protection text exceeds size limit" }
        writeInt(encoded.size)
        write(encoded)
    }

    private fun DataInputStream.text(): String {
        val size = readInt()
        require(size in 0..MAX_TEXT_BYTES && size <= available()) {
            "Invalid protection text length"
        }
        val bytes = ByteArray(size).also(::readFully)
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }

    private fun DataInputStream.count(max: Int): Int = readInt().also {
        require(it in 0..max) { "Invalid protection item count" }
    }

    private fun DataOutputStream.optionalInstant(value: Instant?) {
        writeBoolean(value != null)
        if (value != null) {
            writeLong(value.epochSecond)
            writeInt(value.nano)
        }
    }

    private fun DataInputStream.optionalInstant(): Instant? {
        if (!readBoolean()) return null
        val seconds = readLong()
        val nanos = readInt()
        require(nanos in 0..999_999_999) { "Invalid protection instant nanos" }
        return Instant.ofEpochSecond(seconds, nanos.toLong())
    }
}
