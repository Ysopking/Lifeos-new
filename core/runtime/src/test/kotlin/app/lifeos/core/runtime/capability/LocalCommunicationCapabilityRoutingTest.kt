package app.lifeos.core.runtime.capability

import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class LocalCommunicationCapabilityRoutingTest {
    @Test
    fun `communication uses explicit local share provider`() = runTest {
        val registry = CapabilityRegistry(LanguageGoalCapabilityRouter.LOCAL_SYSTEM_PROVIDERS)
        val result = LanguageGoalCapabilityRouter(registry).route(goal())

        assertTrue(result.ready)
        assertTrue(result.gaps.isEmpty())
        val provider = result.selectedProviders[CapabilityId("communication.dispatch")]
        assertEquals("local-share-core", provider?.providerId)
        assertEquals(TrustLevel.SYSTEM, provider?.trustLevel)
    }

    @Test
    fun `communication remains blocking gap without share provider`() = runTest {
        val result = LanguageGoalCapabilityRouter(CapabilityRegistry()).route(goal())

        assertFalse(result.ready)
        assertEquals(
            listOf(CapabilityId("communication.dispatch")),
            result.blockingGaps.map { it.requirement.capabilityId },
        )
    }

    private fun goal() = GoalFrame(
        intent = IntentType.COMMUNICATE,
        objective = "share result",
        entities = emptyList(),
        references = emptyList(),
        constraints = emptyList(),
        ambiguities = emptyList(),
        confidence = 0.95,
        language = LanguageCode.DE,
    )
}
