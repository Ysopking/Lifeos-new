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
    fun `image creation exposes construction and rasterization gaps when only MMSI exists`() = runTest {
        val registry = CapabilityRegistry()
        registry.register(
            descriptor(
                id = "image.render.mmsi",
                provider = "mmsi-runtime",
                inputs = setOf("mmsi-geometry-buffers"),
                outputs = setOf("image-photon"),
            )
        )

        val result = LanguageGoalCapabilityRouter(registry).route(goal(IntentType.CREATE_IMAGE))

        assertFalse(result.ready)
        assertEquals(1, result.selectedProviders.size)
        assertEquals("mmsi-runtime", result.selectedProviders[CapabilityId("image.render.mmsi")]?.providerId)
        assertTrue(result.gaps.any { it.requirement.capabilityId == CapabilityId("scene.construct.procedural") })
        assertTrue(result.gaps.any { it.requirement.capabilityId == CapabilityId("scene.rasterize.mmsi") })
    }

    @Test
    fun `scene compiler alone still leaves rasterization as honest blocking gap`() = runTest {
        val registry = CapabilityRegistry()
        registry.register(
            descriptor(
                "scene.construct.procedural",
                "scene-core",
                inputs = setOf("goal-photon"),
                outputs = setOf("scene-graph"),
            )
        )
        registry.register(
            descriptor(
                "image.render.mmsi",
                "mmsi-runtime",
                inputs = setOf("mmsi-geometry-buffers"),
                outputs = setOf("image-photon"),
            )
        )

        val result = LanguageGoalCapabilityRouter(registry).route(goal(IntentType.CREATE_IMAGE))

        assertFalse(result.ready)
        assertEquals(setOf(CapabilityId("scene.construct.procedural"), CapabilityId("image.render.mmsi")), result.selectedProviders.keys)
        assertEquals(listOf(CapabilityId("scene.rasterize.mmsi")), result.blockingGaps.map { it.requirement.capabilityId })
    }

    @Test
    fun `image creation becomes routable only when all three image stages satisfy contracts`() = runTest {
        val registry = CapabilityRegistry()
        registry.register(
            descriptor(
                "scene.construct.procedural",
                "scene-core",
                inputs = setOf("goal-photon"),
                outputs = setOf("scene-graph"),
            )
        )
        registry.register(
            descriptor(
                "scene.rasterize.mmsi",
                "scene-rasterizer",
                inputs = setOf("scene-graph"),
                outputs = setOf("mmsi-geometry-buffers"),
            )
        )
        registry.register(
            descriptor(
                "image.render.mmsi",
                "mmsi-runtime",
                inputs = setOf("mmsi-geometry-buffers"),
                outputs = setOf("image-photon"),
            )
        )

        val result = LanguageGoalCapabilityRouter(registry).route(goal(IntentType.CREATE_IMAGE))

        assertTrue(result.ready)
        assertTrue(result.gaps.isEmpty())
        assertEquals(3, result.selectedProviders.size)
    }

    @Test
    fun `scene construction contract mismatch is exposed instead of silently selected`() = runTest {
        val registry = CapabilityRegistry()
        registry.register(
            descriptor(
                "scene.construct.procedural",
                "broken-scene-core",
                inputs = setOf("goal-photon", "scene-template"),
                outputs = setOf("scene-graph"),
            )
        )

        val result = LanguageGoalCapabilityRouter(registry).route(goal(IntentType.CREATE_IMAGE))

        assertTrue(result.gaps.any {
            it.requirement.capabilityId == CapabilityId("scene.construct.procedural") &&
                it.type == CapabilityGapType.CONTRACT_MISMATCH
        })
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
    fun `search uses local DeepSearch provider from shared registry`() = runTest {
        val registry = registryWithLocalSystemProviders()

        val result = LanguageGoalCapabilityRouter(registry).route(goal(IntentType.SEARCH))

        assertTrue(result.ready)
        assertTrue(result.gaps.isEmpty())
        val provider = result.selectedProviders[CapabilityId("deepsearch.query")]
        assertEquals("local-deepsearch-core", provider?.providerId)
        assertEquals(TrustLevel.SYSTEM, provider?.trustLevel)
    }

    @Test
    fun `search stays an honest gap when local DeepSearch executor is absent`() = runTest {
        val result = LanguageGoalCapabilityRouter(CapabilityRegistry()).route(goal(IntentType.SEARCH))

        assertFalse(result.ready)
        assertEquals(
            listOf(CapabilityId("deepsearch.query")),
            result.blockingGaps.map { it.requirement.capabilityId },
        )
    }

    @Test
    fun `query uses local knowledge provider from shared registry`() = runTest {
        val registry = registryWithLocalSystemProviders()

        val result = LanguageGoalCapabilityRouter(registry).route(goal(IntentType.QUERY))

        assertTrue(result.ready)
        assertTrue(result.gaps.isEmpty())
        val provider = result.selectedProviders[CapabilityId("knowledge.resolve")]
        assertEquals("local-knowledge-core", provider?.providerId)
        assertEquals(TrustLevel.SYSTEM, provider?.trustLevel)
        assertEquals(
            "local-knowledge-core",
            registry.providersFor(CapabilityId("knowledge.resolve")).single().providerId,
        )
    }

    @Test
    fun `query stays an honest gap when local executor is absent from registry`() = runTest {
        val result = LanguageGoalCapabilityRouter(CapabilityRegistry()).route(goal(IntentType.QUERY))

        assertFalse(result.ready)
        assertEquals(
            listOf(CapabilityId("knowledge.resolve")),
            result.blockingGaps.map { it.requirement.capabilityId },
        )
    }

    @Test
    fun `schedule uses local reminder provider from shared registry`() = runTest {
        val registry = registryWithLocalSystemProviders()

        val result = LanguageGoalCapabilityRouter(registry).route(goal(IntentType.SCHEDULE))

        assertTrue(result.ready)
        assertTrue(result.gaps.isEmpty())
        val provider = result.selectedProviders[CapabilityId("planner.schedule")]
        assertEquals("local-reminder-core", provider?.providerId)
        assertEquals(TrustLevel.SYSTEM, provider?.trustLevel)
    }

    @Test
    fun `schedule stays an honest gap when local reminder executor is absent`() = runTest {
        val result = LanguageGoalCapabilityRouter(CapabilityRegistry()).route(goal(IntentType.SCHEDULE))

        assertFalse(result.ready)
        assertEquals(
            listOf(CapabilityId("planner.schedule")),
            result.blockingGaps.map { it.requirement.capabilityId },
        )
    }

    @Test
    fun `memory uses local memory provider from shared registry`() = runTest {
        val result = LanguageGoalCapabilityRouter(registryWithLocalSystemProviders())
            .route(goal(IntentType.STORE_OR_REMEMBER))

        assertTrue(result.ready)
        assertTrue(result.gaps.isEmpty())
        assertEquals(
            "local-memory-core",
            result.selectedProviders[CapabilityId("memory.store")]?.providerId,
        )
    }

    @Test
    fun `continue uses local resume provider from shared registry`() = runTest {
        val registry = registryWithLocalSystemProviders()

        val result = LanguageGoalCapabilityRouter(registry).route(goal(IntentType.CONTINUE))

        assertTrue(result.ready)
        assertTrue(result.gaps.isEmpty())
        val provider = result.selectedProviders[CapabilityId("goal.resume")]
        assertEquals("local-goal-resume-core", provider?.providerId)
        assertEquals(TrustLevel.SYSTEM, provider?.trustLevel)
        assertEquals(
            "local-goal-resume-core",
            registry.providersFor(CapabilityId("goal.resume")).single().providerId,
        )
    }

    @Test
    fun `continue stays an honest gap when resume executor is absent from registry`() = runTest {
        val result = LanguageGoalCapabilityRouter(CapabilityRegistry()).route(goal(IntentType.CONTINUE))

        assertFalse(result.ready)
        assertEquals(listOf(CapabilityId("goal.resume")), result.blockingGaps.map { it.requirement.capabilityId })
    }

    @Test
    fun `conversation is local and never creates a capability gap`() = runTest {
        val result = LanguageGoalCapabilityRouter(CapabilityRegistry()).route(goal(IntentType.CONVERSATION))

        assertTrue(result.ready)
        assertTrue(result.plan.requirements.isEmpty())
        assertFalse(result.plan.languageBlocking)
        assertTrue(result.gaps.isEmpty())
    }

    @Test
    fun `unknown language intent never becomes action ready`() = runTest {
        val result = LanguageGoalCapabilityRouter(registryWithLocalSystemProviders()).route(goal(IntentType.UNKNOWN))
        assertFalse(result.ready)
        assertTrue(result.plan.requirements.isEmpty())
        assertTrue(result.plan.languageBlocking)
    }

    private fun registryWithLocalSystemProviders() =
        CapabilityRegistry(LanguageGoalCapabilityRouter.LOCAL_SYSTEM_PROVIDERS)

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
