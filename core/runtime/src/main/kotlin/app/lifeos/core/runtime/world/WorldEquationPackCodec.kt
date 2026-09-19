package app.lifeos.core.runtime.world

import app.lifeos.core.field.world.WorldCoefficientId
import app.lifeos.core.field.world.WorldNodeKind
import app.lifeos.core.field.world.WorldSignalDimension
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

object WorldEquationPackCodec {
    const val VERSION = 1
    const val MAX_ENCODED_BYTES = 8 * 1024 * 1024
    private const val MAX_STRING_BYTES = 1024 * 1024
    private const val MAX_COLLECTION = 4096

    fun encode(pack: WorldEquationPack): ByteArray {
        val equationBytes = WorldEquationSpecCodec.encode(pack.equation)
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(VERSION)
            data.writeString(pack.version)
            data.writeInt(equationBytes.size)
            data.write(equationBytes)

            data.writeString(pack.interactionSchema.version)
            val entries = pack.interactionSchema.stableEntries()
            require(entries.size <= MAX_COLLECTION)
            data.writeInt(entries.size)
            entries.forEach { entry ->
                data.writeString(entry.coefficientId.value)
                data.writeEnumNames(entry.sourceNodeKinds.map { it.name }.sorted())
                data.writeEnumNames(entry.targetNodeKinds.map { it.name }.sorted())
            }

            data.writeString(pack.projectionContract.registrySnapshotId)
            data.writeString(pack.projectionContract.registryFingerprint)
            data.writeEnumNames(pack.projectionContract.requiredProviderIds.sorted())
            data.writeEnumNames(pack.requiredDimensions.map { it.name }.sorted())
            data.writeEnumNames(pack.requiredNodeKinds.map { it.name }.sorted())
            data.writeString(pack.fingerprint())
        }
        return output.toByteArray().also {
            require(it.isNotEmpty() && it.size <= MAX_ENCODED_BYTES) {
                "WorldEquationPack payload size is invalid"
            }
        }
    }

    fun decode(bytes: ByteArray): WorldEquationPack {
        require(bytes.isNotEmpty() && bytes.size <= MAX_ENCODED_BYTES) {
            "WorldEquationPack payload size is invalid"
        }
        return DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            require(data.readInt() == VERSION) {
                "Unsupported WorldEquationPack codec"
            }
            val version = data.readString()
            val equationLength = data.readInt()
            require(equationLength > 0 && equationLength <= WorldEquationSpecCodec.MAX_ENCODED_BYTES) {
                "Invalid WorldEquationPack equation payload length"
            }
            require(equationLength <= data.available()) {
                "Truncated WorldEquationPack equation payload"
            }
            val equation = WorldEquationSpecCodec.decode(
                ByteArray(equationLength).also(data::readFully)
            )
            val interactionVersion = data.readString()
            val entryCount = data.readInt()
            require(entryCount in 1..MAX_COLLECTION) {
                "Invalid WorldEquationPack interaction count"
            }
            val entries = buildList(entryCount) {
                repeat(entryCount) {
                    add(
                        WorldInteractionSchemaEntry(
                            coefficientId = WorldCoefficientId(data.readString()),
                            sourceNodeKinds = data.readEnumNames()
                                .mapTo(linkedSetOf()) { WorldNodeKind.valueOf(it) },
                            targetNodeKinds = data.readEnumNames()
                                .mapTo(linkedSetOf()) { WorldNodeKind.valueOf(it) },
                        )
                    )
                }
            }
            val projection = WorldProjectionContractSnapshot.restore(
                registrySnapshotId = data.readString(),
                registryFingerprint = data.readString(),
                requiredProviderIds = data.readEnumNames().toSet(),
            )
            val requiredDimensions = data.readEnumNames()
                .mapTo(linkedSetOf()) { WorldSignalDimension.valueOf(it) }
            val requiredNodes = data.readEnumNames()
                .mapTo(linkedSetOf()) { WorldNodeKind.valueOf(it) }
            val storedFingerprint = data.readString()
            require(data.available() == 0) {
                "Trailing WorldEquationPack bytes"
            }
            WorldEquationPack(
                version = version,
                equation = equation,
                interactionSchema = WorldInteractionSchema(
                    version = interactionVersion,
                    entries = entries,
                ),
                projectionContract = projection,
                requiredDimensions = requiredDimensions,
                requiredNodeKinds = requiredNodes,
            ).also {
                require(it.fingerprint() == storedFingerprint) {
                    "WorldEquationPack fingerprint does not match content"
                }
            }
        }
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) {
            "WorldEquationPack string too large"
        }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val length = readInt()
        require(length in 0..MAX_STRING_BYTES) {
            "Invalid WorldEquationPack string length"
        }
        val bytes = ByteArray(length)
        readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun DataOutputStream.writeEnumNames(values: List<String>) {
        require(values.size <= MAX_COLLECTION)
        require(values.distinct().size == values.size)
        writeInt(values.size)
        values.forEach { writeString(it) }
    }

    private fun DataInputStream.readEnumNames(): List<String> {
        val count = readInt()
        require(count in 0..MAX_COLLECTION) {
            "Invalid WorldEquationPack collection size"
        }
        return buildList(count) {
            repeat(count) { add(readString()) }
        }.also {
            require(it.distinct().size == it.size) {
                "WorldEquationPack collection contains duplicates"
            }
        }
    }
}
