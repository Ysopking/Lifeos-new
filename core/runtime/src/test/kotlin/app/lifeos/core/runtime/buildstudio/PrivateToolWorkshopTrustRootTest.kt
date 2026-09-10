package app.lifeos.core.runtime.buildstudio

import kotlin.test.Test
import kotlin.test.assertTrue

class PrivateToolWorkshopTrustRootTest {
    @Test
    fun `bounded program and workshop adapters are protected roots`() {
        val policy = BuildPathPolicy()
        assertTrue(
            policy.isProtected(
                "core/runtime/src/main/kotlin/app/lifeos/core/runtime/capability/GeneratedToolProgram.kt"
            )
        )
        assertTrue(
            policy.isProtected(
                "core/runtime/src/main/kotlin/app/lifeos/core/runtime/capability/PrivateToolWorkshopAdapters.kt"
            )
        )
    }
}
