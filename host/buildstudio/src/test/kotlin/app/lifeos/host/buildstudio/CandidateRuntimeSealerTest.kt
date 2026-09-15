package app.lifeos.host.buildstudio

import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CandidateRuntimeSealerTest {
    @Test
    fun `environment provider loads externally provisioned pkcs8 key`() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val provider = EnvironmentCandidateSigningKeyProvider(
            mapOf(
                EnvironmentCandidateSigningKeyProvider.SIGNER_ID_ENV to "buildstudio-host:test",
                EnvironmentCandidateSigningKeyProvider.PRIVATE_KEY_ENV to Base64.getEncoder().encodeToString(pair.private.encoded),
            )
        )

        val loaded = provider.load()
        assertEquals("buildstudio-host:test", loaded.signerId)
        assertTrue(loaded.privateKey.encoded.contentEquals(pair.private.encoded))
    }

    @Test
    fun `environment provider fails closed when signing key is absent`() {
        assertFailsWith<IllegalArgumentException> {
            EnvironmentCandidateSigningKeyProvider(emptyMap()).load()
        }
    }
}
