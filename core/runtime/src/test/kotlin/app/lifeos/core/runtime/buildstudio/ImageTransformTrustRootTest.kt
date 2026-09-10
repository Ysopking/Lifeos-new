package app.lifeos.core.runtime.buildstudio

import kotlin.test.Test
import kotlin.test.assertTrue

class ImageTransformTrustRootTest {
    @Test
    fun `image transform planning execution and provenance are protected`() {
        val policy = BuildPathPolicy()
        listOf(
            "app/src/main/java/app/lifeos/next/kernel/LocalImageTransformActionExecutor.kt",
            "core/image/src/main/kotlin/app/lifeos/core/image/LocalImageTransformEngine.kt",
            "core/image/src/main/kotlin/app/lifeos/core/image/TransformedImagePhotonFactory.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/goal/LocalImageTransformGoalEngine.kt",
        ).forEach { path ->
            assertTrue(policy.isProtected(path), "expected protected image transform root: $path")
        }
    }
}
