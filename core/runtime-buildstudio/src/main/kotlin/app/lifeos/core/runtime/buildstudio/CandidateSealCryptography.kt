package app.lifeos.core.runtime.buildstudio

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * JCA-backed runtime verifier for BuildStudio candidate seals. Only public trust anchors are held
 * by the runtime. Unknown signers, malformed signatures and cryptographic failures fail closed.
 */
class EcdsaCandidateSealVerifier(
    private val trustedSigners: Map<String, PublicKey>,
) : CandidateSealVerifier {
    init {
        require(trustedSigners.keys.none { it.isBlank() })
    }

    override fun verify(seal: CandidateRuntimeSeal): Boolean {
        val publicKey = trustedSigners[seal.signerId] ?: return false
        val signatureBytes = try {
            Base64.getDecoder().decode(seal.signature)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return try {
            Signature.getInstance(SIGNATURE_ALGORITHM).run {
                initVerify(publicKey)
                update(seal.payloadFingerprint.toByteArray(Charsets.UTF_8))
                verify(signatureBytes)
            }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        const val SIGNATURE_ALGORITHM: String = "SHA256withECDSA"

        fun publicKeyFromX509Base64(encoded: String): PublicKey {
            require(encoded.isNotBlank()) { "Candidate signer public key must not be blank" }
            val bytes = Base64.getDecoder().decode(encoded.trim())
            return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
        }
    }
}
