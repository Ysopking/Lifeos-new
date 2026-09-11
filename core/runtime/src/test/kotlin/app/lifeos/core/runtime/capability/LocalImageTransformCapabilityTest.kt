package app.lifeos.core.runtime.capability

import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class LocalImageTransformCapabilityTest {
    @Test
    fun `transform image uses local transform provider from shared registry`() = runTest {
        val registry = CapabilityRegistry(LanguageGoalCapabilityRouter.LOCAL_SYSTEM_PROVIDERS)

        val result = LanguageGoalCapabilityRouter(registry).route(goal())

        assertTrue(result.ready)
        assertTrue(result.gaps.isEmpty())
        val provider = result.selectedProviders[CapabilityId("image.transform.mmsi")]
        assertEquals("local-image-transform-core", provider?.providerId)
        assertEquals(TrustLevel.SYSTEM, provider?.trustLevel)
    }

    @Test
    fun `transform image remains an honest gap without transform provider`() = runTest {
        val result = LanguageGoalCapabilityRouter(CapabilityRegistry()).route(goal())

        assertFalse(result.ready)
        assertEquals(
            listOf(CapabilityId("image.transform.mmsi")),
            result.blockingGaps.map { it.requirement.capabilityId },
        )
    }

    private fun goal() = GoalFrame(
        intent = IntentType.TRANSFORM_IMAGE,
        objective = "Mach dieses Bild heller",
        entities = emptyList(),
        references = emptyList(),
        constraints = emptyList(),
        ambiguities = emptyList(),
        confidence = 0.95,
        language = LanguageCode.DE,
    )
}
