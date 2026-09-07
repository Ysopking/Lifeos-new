package app.lifeos.core.runtime.capability

import app.lifeos.core.language.Ambiguity
import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.LanguageCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class LanguageGoalCapabilityRouterTest {
    @Test
    fun `image creation exposes missing scene construction instead of pretending MMSI is sufficient`() = runTest {
        val registry = CapabilityRegistry()
        registry.register(
            descriptor(
                id = "image.render.mmsi",
                provider = "mmsi-runtime",
                inputs = setOf("scene-geometry"),
                outputs = setOf("image-photon"),
            )
        )

        val result = LanguageGoalCapabilityRouter(registry).route(goal(IntentType.CREATE_IMAGE))

        assertFalse(result.ready)
        assertEquals(1, result.selectedProviders.size)
        assertEquals("mmsi-runtime", result.selectedProviders[CapabilityId("image.render.mmsi")]?.providerId)
        assertTrue(result.gaps.any {
            it.requirement.capabilityId == CapabilityId("scene.construct.procedural") &&
                it.type == CapabilityGapType.CAPABILITY_MISSING
        })
    }

    @Test
    fun `image creation becomes routable when scene and MMSI providers satisfy contracts`() = runTest {
        val registry = CapabilityRegistry()
        registry.register(descriptor("scene.construct.procedural", "scene-core", outputs = setOf("scene-geometry")))
        registry.register(
            descriptor(
                "image.render.mmsi",
                "mmsi-runtime",
                inputs = setOf("scene-geometry"),
                outputs = setOf("image-photon"),
            )
        )

        val result = LanguageGoalCapabilityRouter(registry).route(goal(IntentType.CREATE_IMAGE))

        assertTrue(result.ready)
        assertTrue(result.gaps.isEmpty())
        assertEquals(setOf(CapabilityId("scene.construct.procedural"), CapabilityId("image.render.mmsi")), result.selectedProviders.keys)
    }

    @Test
    fun `severe language ambiguity blocks action even when provider exists`() = runTest {
        val registry = CapabilityRegistry()
        registry.register(
            descriptor(
                "image.transform.mmsi",
                "mmsi-transform",
                inputs = setOf("image-photon"),
                outputs = setOf("image-photon"),
            )
        )
        val ambiguous = goal(IntentType.TRANSFORM_IMAGE).copy(
            ambiguities = listOf(Ambiguity("image_source_missing", "source missing", emptyList(), 0.92))
        )

        val result = LanguageGoalCapabilityRouter(registry).route(ambiguous)

        assertFalse(result.ready)
        assertTrue(result.plan.languageBlocking)
        assertTrue(result.gaps.isEmpty())
    }

    @Test
    fun `unknown language intent never becomes action ready`() = runTest {
        val result = LanguageGoalCapabilityRouter(CapabilityRegistry()).route(goal(IntentType.UNKNOWN))
        assertFalse(result.ready)
        assertTrue(result.plan.requirements.isEmpty())
        assertTrue(result.plan.languageBlocking)
    }

    private fun goal(intent: IntentType) = GoalFrame(
        intent = intent,
        objective = "test",
        entities = emptyList(),
        references = emptyList(),
        constraints = emptyList(),
        ambiguities = emptyList(),
        confidence = 0.95,
        language = LanguageCode.DE,
    )

    private fun descriptor(
        id: String,
        provider: String,
        inputs: Set<String> = emptySet(),
        outputs: Set<String> = emptySet(),
    ) = CapabilityDescriptor(
        capabilityId = CapabilityId(id),
        providerId = provider,
        providerType = ProviderType.MODULE,
        contract = CapabilityContract(requiredInputs = inputs, outputs = outputs),
        state = ProviderState.ACTIVE,
        trustLevel = TrustLevel.SYSTEM,
    )
}
