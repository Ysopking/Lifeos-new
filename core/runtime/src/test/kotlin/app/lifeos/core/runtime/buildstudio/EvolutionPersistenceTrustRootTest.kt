package app.lifeos.core.runtime.buildstudio

import kotlin.test.Test
import kotlin.test.assertTrue

class EvolutionPersistenceTrustRootTest {
    @Test
    fun `generated candidates cannot rewrite durable evolution persistence`() {
        val policy = BuildPathPolicy()

        assertTrue(
            policy.isProtected(
                "core/data/src/main/kotlin/app/lifeos/core/data/evolution/EncryptedEvolutionStore.kt"
            )
        )
        assertTrue(
            policy.isProtected(
                "core/data/src/main/kotlin/app/lifeos/core/data/evolution/FutureEvolutionRepository.kt"
            )
        )
    }
}
