package app.lifeos.core.runtime.buildstudio

import kotlin.test.Test
import kotlin.test.assertTrue

class J11BootTrustRootTest {
    @Test
    fun `boot and generated-tool rehydration integration are protected`() {
        val policy = BuildPathPolicy()

        assertTrue(
            policy.isProtected(
                "core/runtime/src/main/kotlin/app/lifeos/core/runtime/boot/ChainedStateRehydrator.kt"
            )
        )
        assertTrue(
            policy.isProtected(
                "core/runtime/src/main/kotlin/app/lifeos/core/runtime/capability/GeneratedToolBootStateRehydrator.kt"
            )
        )
        assertTrue(
            policy.isProtected(
                "app/src/main/java/app/lifeos/next/kernel/EvolutionRuntimeResources.kt"
            )
        )
        assertTrue(
            policy.isProtected(
                "app/src/main/java/app/lifeos/next/kernel/LifeOsKernelFactory.kt"
            )
        )
    }
}
