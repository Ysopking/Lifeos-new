package app.lifeos.core.runtime.buildstudio

import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

fun interface CandidatePrivateSigningKeyProvider {
    fun privateKey(signerId: String): PrivateKey?
}

fun interface TrustedCandidatePublicKeyProvider {
    fun publicKey(signerId: String): PublicKey?
}

/** Host-side sealer. Private keys are injected by the host and are never embedded in LIFEOS. */
class CandidateRuntimeSealer(
    private val signerId: String,
    private val keyProvider: CandidatePrivateSigningKeyProvider,
) {
    init { require(signerId.isNotBlank()) }

    fun seal(artifact: CandidateArtifact): CandidateRuntimeSeal {
        val privateKey = requireNotNull(keyProvider.privateKey(signerId)) {
            "No BuildStudio signing key configured for signer $signerId"
        }
        val payloadFingerprint = CandidateRuntimeSeal.payloadFingerprint(
            candidateArtifactId = artifact.id,
            candidateId = artifact.candidate.id,
            sourceCommit = artifact.sourceCommit,
            branchHeadCommit = artifact.branchHeadCommit,
            verificationId = artifact.verification.id,
            provenanceId = artifact.provenance.id,
            debugApkSha256 = artifact.debugApkSha256.lowercase(),
            signerId = signerId,
        )
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(privateKey)
            update(payloadFingerprint.toByteArray(StandardCharsets.UTF_8))
            Base64.getEncoder().encodeToString(sign())
        }
        return CandidateRuntimeSeal(
            candidateArtifactId = artifact.id,
            candidateId = artifact.candidate.id,
            sourceCommit = artifact.sourceCommit,
            branchHeadCommit = artifact.branchHeadCommit,
            verificationId = artifact.verification.id,
            provenanceId = artifact.provenance.id,
            debugApkSha256 = artifact.debugApkSha256.lowercase(),
            signerId = signerId,
            signature = signature,
        )
    }

    companion object {
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    }
}

/** Runtime verifier backed only by explicitly trusted public keys. Unknown signer ids fail closed. */
class EcdsaCandidateSealVerifier(
    private val trustedKeys: TrustedCandidatePublicKeyProvider,
) : CandidateSealVerifier {
    override fun verify(seal: CandidateRuntimeSeal): Boolean {
        val publicKey = trustedKeys.publicKey(seal.signerId) ?: return false
        return runCatching {
            val signatureBytes = Base64.getDecoder().decode(seal.signature)
            Signature.getInstance(CandidateRuntimeSealer.SIGNATURE_ALGORITHM).run {
                initVerify(publicKey)
                update(seal.payloadFingerprint.toByteArray(StandardCharsets.UTF_8))
                verify(signatureBytes)
            }
        }.getOrDefault(false)
    }
}

/** Encoding helpers only; they never provide or persist a key by themselves. */
object CandidateSigningKeyCodec {
    fun decodeEcPrivateKey(pkcs8Base64: String): PrivateKey = KeyFactory.getInstance("EC")
        .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pkcs8Base64)))

    fun decodeEcPublicKey(x509Base64: String): PublicKey = KeyFactory.getInstance("EC")
        .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(x509Base64)))
}
