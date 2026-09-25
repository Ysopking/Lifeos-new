package app.lifeos.core.runtime.boot

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WarmRuntimeReadinessRegistryTest {
    @AfterTest
    fun cleanup() {
        WarmRuntimeReadinessRegistry.clearForTests()
    }

    @Test
    fun warmingFeatureFailsClosedUntilReady() {
        WarmRuntimeReadinessRegistry.beginBoot(setOf(WarmRuntimeFeature.DEEP_SEARCH))

        assertFailsWith<IllegalStateException> {
            WarmRuntimeReadinessRegistry.requireReady(WarmRuntimeFeature.DEEP_SEARCH)
        }

        WarmRuntimeReadinessRegistry.markReady(WarmRuntimeFeature.DEEP_SEARCH)
        WarmRuntimeReadinessRegistry.requireReady(WarmRuntimeFeature.DEEP_SEARCH)
        assertEquals(
            WarmRuntimeFeatureStatus.READY,
            WarmRuntimeReadinessRegistry.state(WarmRuntimeFeature.DEEP_SEARCH).status,
        )
    }

    @Test
    fun failedFeatureRetainsExactFailureAndNotRequiredLegacyPathRemainsUsable() {
        WarmRuntimeReadinessRegistry.beginBoot(setOf(WarmRuntimeFeature.GENERATED_TOOLS))
        WarmRuntimeReadinessRegistry.markFailed(
            WarmRuntimeFeature.GENERATED_TOOLS,
            "restore-failed",
        )

        val failure = assertFailsWith<IllegalStateException> {
            WarmRuntimeReadinessRegistry.requireReady(WarmRuntimeFeature.GENERATED_TOOLS)
        }
        check("restore-failed" in failure.message.orEmpty())

        WarmRuntimeReadinessRegistry.requireReady(WarmRuntimeFeature.DEEP_SEARCH)
    }
}
