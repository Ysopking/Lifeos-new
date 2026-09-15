package app.lifeos.core.runtime.buildstudio

import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CandidateSealCryptographyTest {
    @Test
    fun `ecdsa trust anchor accepts exact signed payload and rejects tampering`() {
        val trusted = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val other = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val unsigned = seal(signature = "pending")
        val signature = Signature.getInstance(EcdsaCandidateSealVerifier.SIGNATURE_ALGORITHM).run {
            initSign(trusted.private)
            update(unsigned.payloadFingerprint.toByteArray(Charsets.UTF_8))
            Base64.getEncoder().encodeToString(sign())
        }
        val signed = unsigned.copy(signature = signature)
        val verifier = EcdsaCandidateSealVerifier(mapOf(SIGNER to trusted.public))

        assertTrue(verifier.verify(signed))
        assertFalse(EcdsaCandidateSealVerifier(mapOf(SIGNER to other.public)).verify(signed))
        assertFalse(verifier.verify(signed.copy(provenanceId = "tampered-provenance")))
        assertFalse(verifier.verify(signed.copy(signerId = "unknown-signer")))
    }

    @Test
    fun `x509 public key parser preserves trust anchor`() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val encoded = Base64.getEncoder().encodeToString(pair.public.encoded)
        val parsed = EcdsaCandidateSealVerifier.publicKeyFromX509Base64(encoded)
        assertTrue(parsed.encoded.contentEquals(pair.public.encoded))
    }

    private fun seal(signature: String) = CandidateRuntimeSeal(
        candidateArtifactId = "artifact-id",
        candidateId = "candidate-id",
        sourceCommit = "a".repeat(40),
        branchHeadCommit = "b".repeat(40),
        verificationId = "verification-id",
        provenanceId = "provenance-id",
        debugApkSha256 = "c".repeat(64),
        signerId = SIGNER,
        signature = signature,
    )

    companion object { private const val SIGNER = "buildstudio-host:prod" }
}
