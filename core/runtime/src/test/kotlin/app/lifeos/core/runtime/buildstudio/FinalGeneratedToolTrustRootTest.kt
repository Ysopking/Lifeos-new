package app.lifeos.core.runtime.buildstudio

import kotlin.test.Test
import kotlin.test.assertTrue

class FinalGeneratedToolTrustRootTest {
    @Test
    fun `generated tool executable and approval boundaries are protected`() {
        val policy = BuildPathPolicy()
        listOf(
            "app/src/main/java/app/lifeos/next/kernel/PrivateGeneratedToolRuntimeResources.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/capability/GeneratedToolRequestPhotonCodec.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/capability/GeneratedToolArtifact.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/capability/GeneratedToolArtifactBootVerifier.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/capability/GeneratedToolTrialRunner.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/capability/GeneratedToolActivationEvidence.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/capability/BoundedGeneratedToolPromotionEvidence.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/capability/BoundedGeneratedToolStateRepository.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/capability/BoundedGeneratedToolStateRehydrator.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/evolution/NovelCapabilityPromotion.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/evolution/BoundedNovelPromotionCoordinator.kt",
            "core/runtime/src/main/kotlin/app/lifeos/core/runtime/evolution/PrivateNovelCapabilityActivationCoordinator.kt",
        ).forEach { path -> assertTrue(policy.isProtected(path), path) }
    }
}
