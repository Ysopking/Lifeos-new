package app.lifeos.core.runtime.world

import app.lifeos.core.field.world.WorldCoefficientId
import app.lifeos.core.field.world.WorldEquationSpec
import app.lifeos.core.field.world.WorldSignalDimension
import app.lifeos.core.field.world.WorldTransferCoefficient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

object WorldEquationSpecCodec {
    const val VERSION = 1
    const val MAX_ENCODED_BYTES = 4 * 1024 * 1024
    private const val MAX_COEFFICIENTS = 4096
    private const val MAX_STRING_BYTES = 1024 * 1024

    fun encode(spec: WorldEquationSpec): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(VERSION)
            data.writeString(spec.version)
            val coefficients = spec.stableCoefficients()
            data.writeInt(coefficients.size)
            coefficients.forEach { coefficient ->
                data.writeString(coefficient.id.value)
                data.writeString(coefficient.sourceDimension.name)
                data.writeString(coefficient.targetDimension.name)
                data.writeDouble(coefficient.multiplier)
                data.writeDouble(coefficient.confidenceMultiplier)
                data.writeDouble(coefficient.maxAbsoluteContribution)
                data.writeString(coefficient.explanation)
            }
        }
        return output.toByteArray().also {
            require(it.isNotEmpty() && it.size <= MAX_ENCODED_BYTES) {
                "World equation spec payload size is invalid"
            }
        }
    }

    fun decode(bytes: ByteArray): WorldEquationSpec {
        require(bytes.isNotEmpty() && bytes.size <= MAX_ENCODED_BYTES) {
            "World equation spec payload size is invalid"
        }
        return DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            require(data.readInt() == VERSION) { "Unsupported world equation spec codec" }
            val version = data.readString()
            val count = data.readInt()
            require(count in 0..MAX_COEFFICIENTS) {
                "Invalid world equation coefficient count"
            }
            val coefficients = buildList(count) {
                repeat(count) {
                    add(
                        WorldTransferCoefficient(
                            id = WorldCoefficientId(data.readString()),
                            sourceDimension = WorldSignalDimension.valueOf(data.readString()),
                            targetDimension = WorldSignalDimension.valueOf(data.readString()),
                            multiplier = data.readDouble(),
                            confidenceMultiplier = data.readDouble(),
                            maxAbsoluteContribution = data.readDouble(),
                            explanation = data.readString(),
                        )
                    )
                }
            }
            require(data.available() == 0) { "Trailing world equation spec bytes" }
            WorldEquationSpec(version = version, coefficients = coefficients)
        }
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "World equation spec string too large" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val length = readInt()
        require(length in 0..MAX_STRING_BYTES) { "Invalid world equation spec string length" }
        val bytes = ByteArray(length)
        readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }
}
