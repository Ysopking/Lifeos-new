package app.lifeos.host.buildstudio

import app.lifeos.core.runtime.buildstudio.CandidateArtifact
import app.lifeos.core.runtime.buildstudio.CandidateRuntimeSeal
import app.lifeos.core.runtime.buildstudio.EcdsaCandidateSealVerifier
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

data class CandidateSigningKey(
    val signerId: String,
    val privateKey: PrivateKey,
) {
    init { require(signerId.isNotBlank()) }
}

fun interface CandidateSigningKeyProvider {
    fun load(): CandidateSigningKey
}

/**
 * Host-only key provider. The private key must be provisioned externally as PKCS#8 base64 and is
 * never read from repository files. Missing or malformed configuration fails closed.
 */
class EnvironmentCandidateSigningKeyProvider(
    private val environment: Map<String, String> = System.getenv(),
) : CandidateSigningKeyProvider {
    override fun load(): CandidateSigningKey {
        val signerId = requireNotNull(environment[SIGNER_ID_ENV]) {
            "$SIGNER_ID_ENV is required for BuildStudio candidate sealing"
        }.trim()
        require(signerId.isNotBlank()) { "$SIGNER_ID_ENV must not be blank" }
        val encoded = requireNotNull(environment[PRIVATE_KEY_ENV]) {
            "$PRIVATE_KEY_ENV is required for BuildStudio candidate sealing"
        }.trim()
        require(encoded.isNotBlank()) { "$PRIVATE_KEY_ENV must not be blank" }
        val keyBytes = Base64.getDecoder().decode(encoded)
        val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
        return CandidateSigningKey(signerId, key)
    }

    companion object {
        const val SIGNER_ID_ENV = "LIFEOS_BUILDSTUDIO_SIGNER_ID"
        const val PRIVATE_KEY_ENV = "LIFEOS_BUILDSTUDIO_SIGNING_KEY_PKCS8_B64"
    }
}

/** Seals only a complete immutable CandidateArtifact. It does not publish or activate anything. */
class EcdsaCandidateRuntimeSealer(
    private val keyProvider: CandidateSigningKeyProvider,
) {
    fun seal(artifact: CandidateArtifact): CandidateRuntimeSeal {
        require(!artifact.activationAllowed)
        val key = keyProvider.load()
        val unsigned = CandidateRuntimeSeal(
            candidateArtifactId = artifact.id,
            candidateId = artifact.candidate.id,
            sourceCommit = artifact.sourceCommit,
            branchHeadCommit = artifact.branchHeadCommit,
            verificationId = artifact.verification.id,
            provenanceId = artifact.provenance.id,
            debugApkSha256 = artifact.debugApkSha256.lowercase(),
            signerId = key.signerId,
            signature = PENDING_SIGNATURE,
        )
        val signature = Signature.getInstance(EcdsaCandidateSealVerifier.SIGNATURE_ALGORITHM).run {
            initSign(key.privateKey)
            update(unsigned.payloadFingerprint.toByteArray(Charsets.UTF_8))
            Base64.getEncoder().encodeToString(sign())
        }
        return unsigned.copy(signature = signature)
    }

    private companion object {
        const val PENDING_SIGNATURE = "pending-host-signature"
    }
}
