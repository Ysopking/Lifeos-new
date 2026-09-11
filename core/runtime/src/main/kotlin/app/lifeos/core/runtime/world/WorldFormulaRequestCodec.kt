package app.lifeos.core.runtime.world

import app.lifeos.core.field.world.WorldCoefficientId
import app.lifeos.core.field.world.WorldDimensionValue
import app.lifeos.core.field.world.WorldFieldVector
import app.lifeos.core.field.world.WorldNodeKind
import app.lifeos.core.field.world.WorldSignalDimension
import app.lifeos.core.field.world.WorldTargetRef
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.task.TaskId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant

/** Canonical bounded codec for a deterministic WorldFormulaRequest used by the crash-safe V4 outbox. */
object WorldFormulaRequestCodec {
    const val MAX_PAYLOAD_BYTES: Int = 32 * 1024 * 1024
    private const val VERSION = 1
    private const val MAX_INPUTS = 10_000
    private const val MAX_INTERACTIONS = 100_000
    private const val MAX_DIMENSIONS = 128
    private const val MAX_PROVENANCE = 4096
    private const val MAX_STRING_BYTES = 256 * 1024

    fun encode(request: WorldFormulaRequest): ByteArray {
        require(request.inputs.size <= MAX_INPUTS) { "Too many world formula inputs" }
        require(request.interactions.size <= MAX_INTERACTIONS) { "Too many world formula interactions" }
        return ByteArrayOutputStream().let { output ->
            DataOutputStream(output).use { out ->
                out.writeInt(VERSION)
                writeString(out, request.id)
                writeString(out, request.equationVersion)
                writeInstant(out, request.observedAt)
                out.writeInt(request.config.maxIterations)
                out.writeInt(request.config.requiredStableRounds)
                out.writeDouble(request.config.epsilon)
                out.writeDouble(request.config.opposingContributionThreshold)
                writeNullableString(out, request.sourceTaskId?.value)
                writeNullableString(out, request.photonId?.value)
                out.writeInt(request.inputs.size)
                request.inputs.sortedWith(compareBy({ it.target.kind.name }, { it.target.key })).forEach { input ->
                    writeTarget(out, input.target)
                    writeString(out, input.sourceSnapshotFingerprint)
                    val values = input.vector.stableValues()
                    require(values.size <= MAX_DIMENSIONS) { "Too many world dimensions" }
                    out.writeInt(values.size)
                    values.forEach { value ->
                        writeString(out, value.dimension.name)
                        out.writeDouble(value.value)
                        out.writeDouble(value.confidence)
                        val provenance = value.provenanceFingerprints.sorted()
                        require(provenance.size <= MAX_PROVENANCE) { "Too many world provenance fingerprints" }
                        out.writeInt(provenance.size)
                        provenance.forEach { writeString(out, it) }
                    }
                }
                out.writeInt(request.interactions.size)
                request.interactions.sortedBy { it.fingerprint() }.forEach { interaction ->
                    writeTarget(out, interaction.source)
                    writeTarget(out, interaction.target)
                    writeString(out, interaction.sourceDimension.name)
                    writeString(out, interaction.targetDimension.name)
                    writeString(out, interaction.coefficientId.value)
                    out.writeDouble(interaction.strength)
                    writeString(out, interaction.explanation)
                }
            }
            output.toByteArray()
        }.also {
            require(it.isNotEmpty() && it.size <= MAX_PAYLOAD_BYTES) { "World formula request payload too large" }
        }
    }

    fun decode(payload: ByteArray): WorldFormulaRequest {
        require(payload.isNotEmpty() && payload.size <= MAX_PAYLOAD_BYTES) {
            "Invalid world formula request payload size"
        }
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == VERSION) { "Unsupported world formula request version" }
            val expectedId = readString(input)
            val equationVersion = readString(input)
            val observedAt = readInstant(input)
            val config = WorldFormulaConfig(
                maxIterations = input.readInt(),
                requiredStableRounds = input.readInt(),
                epsilon = input.readDouble(),
                opposingContributionThreshold = input.readDouble(),
            )
            val sourceTaskId = readNullableString(input)?.let(::TaskId)
            val photonId = readNullableString(input)?.let(::PhotonId)
            val inputs = List(readCount(input, MAX_INPUTS, "inputs")) {
                val target = readTarget(input)
                val sourceFingerprint = readString(input)
                val values = List(readCount(input, MAX_DIMENSIONS, "dimensions")) {
                    val dimension = enumValueOf<WorldSignalDimension>(readString(input))
                    val value = input.readDouble()
                    val confidence = input.readDouble()
                    val provenance = List(readCount(input, MAX_PROVENANCE, "provenance")) {
                        readString(input)
                    }.toSet()
                    WorldDimensionValue(dimension, value, confidence, provenance)
                }
                WorldFormulaInputSnapshot(
                    target = target,
                    vector = WorldFieldVector(values),
                    sourceSnapshotFingerprint = sourceFingerprint,
                )
            }
            val interactions = List(readCount(input, MAX_INTERACTIONS, "interactions")) {
                WorldFormulaInteraction(
                    source = readTarget(input),
                    target = readTarget(input),
                    sourceDimension = enumValueOf(readString(input)),
                    targetDimension = enumValueOf(readString(input)),
                    coefficientId = WorldCoefficientId(readString(input)),
                    strength = input.readDouble(),
                    explanation = readString(input),
                )
            }
            require(input.available() == 0) { "Trailing world formula request data" }
            WorldFormulaRequest(
                inputs = inputs,
                interactions = interactions,
                equationVersion = equationVersion,
                observedAt = observedAt,
                config = config,
                sourceTaskId = sourceTaskId,
                photonId = photonId,
            ).also { request ->
                require(request.id == expectedId) { "World formula request identity mismatch" }
            }
        }
    }

    private fun writeTarget(out: DataOutputStream, target: WorldTargetRef) {
        writeString(out, target.kind.name)
        writeString(out, target.key)
    }

    private fun readTarget(input: DataInputStream): WorldTargetRef = WorldTargetRef(
        kind = enumValueOf<WorldNodeKind>(readString(input)),
        key = readString(input),
    )

    private fun writeInstant(out: DataOutputStream, value: Instant) {
        out.writeLong(value.epochSecond)
        out.writeInt(value.nano)
    }

    private fun readInstant(input: DataInputStream): Instant =
        Instant.ofEpochSecond(input.readLong(), input.readInt().toLong())

    private fun writeNullableString(out: DataOutputStream, value: String?) {
        out.writeBoolean(value != null)
        if (value != null) writeString(out, value)
    }

    private fun readNullableString(input: DataInputStream): String? =
        if (input.readBoolean()) readString(input) else null

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "World formula request string too large" }
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val length = input.readInt()
        require(length in 0..MAX_STRING_BYTES && length <= input.available()) {
            "Invalid world formula request string length"
        }
        return ByteArray(length).also(input::readFully).toString(StandardCharsets.UTF_8)
    }

    private fun readCount(input: DataInputStream, max: Int, label: String): Int =
        input.readInt().also { require(it in 0..max) { "Invalid world formula $label count" } }
}
