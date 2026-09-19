package app.lifeos.core.runtime.world

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

object WorldEquationPackStructuralValidationCodec {
    const val VERSION = 1
    const val MAX_ENCODED_BYTES = 1024 * 1024
    private const val MAX_STRING_BYTES = 256 * 1024

    fun encode(
        bundle: WorldEquationPackStructuralValidationBundle,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(VERSION)
            data.writeString(bundle.baselinePackVersion)
            data.writeString(bundle.baselinePackFingerprint)
            data.writeString(bundle.baselineStructuralFingerprint)
            data.writeString(bundle.candidatePackVersion)
            data.writeString(bundle.candidatePackFingerprint)
            data.writeString(bundle.candidateStructuralFingerprint)
            data.writeString(bundle.structuralPreflightId)
            data.writeString(bundle.evidenceRecordFingerprint)
            data.writeString(bundle.evidenceSetFingerprint)
            data.writeString(bundle.shadowAssessmentId)
            data.writeString(bundle.registrySnapshotId)
            data.writeString(bundle.registryFingerprint)
            data.writeString(bundle.protocolFingerprint)
            data.writeString(bundle.fingerprint)
        }
        return output.toByteArray().also {
            require(it.isNotEmpty() && it.size <= MAX_ENCODED_BYTES) {
                "Structural validation payload size is invalid"
            }
        }
    }

    fun decode(bytes: ByteArray): WorldEquationPackStructuralValidationBundle {
        require(bytes.isNotEmpty() && bytes.size <= MAX_ENCODED_BYTES) {
            "Structural validation payload size is invalid"
        }
        return DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            require(data.readInt() == VERSION) {
                "Unsupported structural validation codec"
            }
            val baselinePackVersion = data.readString()
            val baselinePackFingerprint = data.readString()
            val baselineStructuralFingerprint = data.readString()
            val candidatePackVersion = data.readString()
            val candidatePackFingerprint = data.readString()
            val candidateStructuralFingerprint = data.readString()
            val structuralPreflightId = data.readString()
            val evidenceRecordFingerprint = data.readString()
            val evidenceSetFingerprint = data.readString()
            val shadowAssessmentId = data.readString()
            val registrySnapshotId = data.readString()
            val registryFingerprint = data.readString()
            val protocolFingerprint = data.readString()
            val storedFingerprint = data.readString()
            require(data.available() == 0) {
                "Trailing structural validation bytes"
            }
            WorldEquationPackStructuralValidationBundle.restore(
                baselinePackVersion = baselinePackVersion,
                baselinePackFingerprint = baselinePackFingerprint,
                baselineStructuralFingerprint = baselineStructuralFingerprint,
                candidatePackVersion = candidatePackVersion,
                candidatePackFingerprint = candidatePackFingerprint,
                candidateStructuralFingerprint = candidateStructuralFingerprint,
                structuralPreflightId = structuralPreflightId,
                evidenceRecordFingerprint = evidenceRecordFingerprint,
                evidenceSetFingerprint = evidenceSetFingerprint,
                shadowAssessmentId = shadowAssessmentId,
                registrySnapshotId = registrySnapshotId,
                registryFingerprint = registryFingerprint,
                protocolFingerprint = protocolFingerprint,
                fingerprint = storedFingerprint,
            )
        }
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) {
            "Structural validation string too large"
        }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val length = readInt()
        require(length in 0..MAX_STRING_BYTES) {
            "Invalid structural validation string length"
        }
        val bytes = ByteArray(length)
        readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }
}
