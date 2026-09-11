package app.lifeos.core.runtime.buildstudio

import kotlin.test.Test
import kotlin.test.assertTrue

class GeneratedToolRequestTrustRootTest {
    private val policy = BuildPathPolicy()

    @Test
    fun `generated tool request approval user action and private trial roots are protected`() {
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
        assertTrue(
            policy.isProtected(
                "core/runtime/src/main/kotlin/app/lifeos/core/runtime/capability/GeneratedToolUserActionCoordinator.kt"
            )
        )
        assertTrue(
            policy.isProtected(
                "core/runtime/src/main/kotlin/app/lifeos/core/runtime/capability/PrivateGeneratedToolTrialSuite.kt"
            )
        )
    }
}
