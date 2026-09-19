package app.lifeos.core.runtime.world

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

object WorldEquationPackStructuralCanaryPlanCodec {
    const val VERSION = 1
    const val MAX_ENCODED_BYTES = 256 * 1024
    private const val MAX_STRING_BYTES = 64 * 1024

    fun encode(plan: WorldEquationPackStructuralCanaryPlan): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(VERSION)
            data.writeString(plan.validationBundleFingerprint)
            data.writeString(plan.baselinePackFingerprint)
            data.writeString(plan.candidatePackFingerprint)
            data.writeInt(plan.maximumCases)
            data.writeInt(plan.maximumConsecutiveFailures)
            data.writeBoolean(plan.requireHoldoutRecheck)
            data.writeBoolean(plan.requireColdRestartRecovery)
            data.writeString(plan.version)
            data.writeString(plan.fingerprint)
        }
        return output.toByteArray().also {
            require(it.isNotEmpty() && it.size <= MAX_ENCODED_BYTES)
        }
    }

    fun decode(bytes: ByteArray): WorldEquationPackStructuralCanaryPlan {
        require(bytes.isNotEmpty() && bytes.size <= MAX_ENCODED_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            require(data.readInt() == VERSION) {
                "Unsupported structural canary plan codec"
            }
            val validationBundleFingerprint = data.readString()
            val baselinePackFingerprint = data.readString()
            val candidatePackFingerprint = data.readString()
            val maximumCases = data.readInt()
            val maximumConsecutiveFailures = data.readInt()
            val requireHoldoutRecheck = data.readBoolean()
            val requireColdRestartRecovery = data.readBoolean()
            val version = data.readString()
            val fingerprint = data.readString()
            require(data.available() == 0) {
                "Trailing structural canary plan bytes"
            }
            WorldEquationPackStructuralCanaryPlan.restore(
                validationBundleFingerprint = validationBundleFingerprint,
                baselinePackFingerprint = baselinePackFingerprint,
                candidatePackFingerprint = candidatePackFingerprint,
                maximumCases = maximumCases,
                maximumConsecutiveFailures = maximumConsecutiveFailures,
                requireHoldoutRecheck = requireHoldoutRecheck,
                requireColdRestartRecovery = requireColdRestartRecovery,
                version = version,
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
