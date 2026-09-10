package app.lifeos.core.runtime.buildstudio

import kotlin.test.Test
import kotlin.test.assertTrue

class GeneratedToolRequestTrustRootTest {
    private val policy = BuildPathPolicy()

    @Test
    fun `generated tool request and approval coordinator are protected roots`() {
        assertTrue(
            policy.isProtected(
                "core/runtime/src/main/kotlin/app/lifeos/core/runtime/capability/GeneratedToolRequest.kt"
            )
        )
        assertTrue(
            policy.isProtected(
                "core/runtime/src/main/kotlin/app/lifeos/core/runtime/capability/GeneratedToolRequestCoordinator.kt"
            )
        )
    }
}
