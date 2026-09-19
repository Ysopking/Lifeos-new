package app.lifeos.core.runtime.world

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

object WorldEquationPackStructuralPromotionReviewCodec {
    const val VERSION = 1
    const val MAX_ENCODED_BYTES = 256 * 1024
    private const val MAX_STRING_BYTES = 64 * 1024

    fun encode(
        bundle: WorldEquationPackStructuralPromotionReviewBundle,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(VERSION)
            data.writeString(bundle.validationBundleFingerprint)
            data.writeString(bundle.canaryPlanFingerprint)
            data.writeString(bundle.canaryEvidenceRecordFingerprint)
            data.writeString(bundle.canaryAssessmentId)
            data.writeString(bundle.baselinePackFingerprint)
            data.writeString(bundle.candidatePackFingerprint)
            data.writeString(bundle.fingerprint)
        }
        return output.toByteArray().also {
            require(it.isNotEmpty() && it.size <= MAX_ENCODED_BYTES)
        }
    }

    fun decode(
        bytes: ByteArray,
    ): WorldEquationPackStructuralPromotionReviewBundle {
        require(bytes.isNotEmpty() && bytes.size <= MAX_ENCODED_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            require(data.readInt() == VERSION) {
                "Unsupported structural promotion review codec"
            }
            val validationBundleFingerprint = data.readString()
            val canaryPlanFingerprint = data.readString()
            val canaryEvidenceRecordFingerprint = data.readString()
            val canaryAssessmentId = data.readString()
            val baselinePackFingerprint = data.readString()
            val candidatePackFingerprint = data.readString()
            val fingerprint = data.readString()
            require(data.available() == 0) {
                "Trailing structural promotion review bytes"
            }
            WorldEquationPackStructuralPromotionReviewBundle.restore(
                validationBundleFingerprint = validationBundleFingerprint,
                canaryPlanFingerprint = canaryPlanFingerprint,
                canaryEvidenceRecordFingerprint = canaryEvidenceRecordFingerprint,
                canaryAssessmentId = canaryAssessmentId,
                baselinePackFingerprint = baselinePackFingerprint,
                candidatePackFingerprint = candidatePackFingerprint,
                fingerprint = fingerprint,
            )
        }
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val length = readInt()
        require(length in 0..MAX_STRING_BYTES)
        val bytes = ByteArray(length)
        readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }
}
