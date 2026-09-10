package app.lifeos.core.runtime.capability

import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType

data class GoalCapabilityPlan(
    val goal: GoalFrame,
    val requirements: List<CapabilityRequirement>,
    val languageBlocking: Boolean,
)

data class GoalCapabilityResolution(
    val plan: GoalCapabilityPlan,
    val selectedProviders: Map<CapabilityId, CapabilityDescriptor>,
    val gaps: List<CapabilityGap>,
) {
    val blockingGaps: List<CapabilityGap> = gaps.filter {
        it.requirement.severity == GapSeverity.BLOCKING || it.requirement.severity == GapSeverity.CRITICAL
    }
    val ready: Boolean = !plan.languageBlocking && blockingGaps.isEmpty()
}

class LanguageGoalCapabilityMapper {
    fun plan(goal: GoalFrame): GoalCapabilityPlan {
        val requirements = when (goal.intent) {
            IntentType.CREATE_IMAGE -> listOf(
                requirement("scene.construct.procedural", inputs = setOf("goal-photon"), outputs = setOf("scene-graph")),
                requirement("scene.rasterize.mmsi", inputs = setOf("scene-graph"), outputs = setOf("mmsi-geometry-buffers")),
                requirement("image.render.mmsi", inputs = setOf("mmsi-geometry-buffers"), outputs = setOf("image-photon")),
            )
            IntentType.TRANSFORM_IMAGE -> listOf(
                requirement("image.transform.mmsi", inputs = setOf("image-photon"), outputs = setOf("image-photon")),
            )
            IntentType.SEARCH -> listOf(requirement("deepsearch.query", outputs = setOf("reference-photons")))
            IntentType.CONTINUE -> listOf(requirement("goal.resume", inputs = setOf("goal-photon"), outputs = setOf("goal-photon")))
            IntentType.BUILD_OR_IMPLEMENT -> listOf(requirement("buildstudio.run", inputs = setOf("goal-photon"), outputs = setOf("build-artifact")))
            IntentType.QUERY -> listOf(requirement("knowledge.resolve", inputs = setOf("goal-photon"), outputs = setOf("answer-photon")))
            IntentType.SCHEDULE -> listOf(requirement("planner.schedule", inputs = setOf("goal-photon"), outputs = setOf("scheduled-action")))
            IntentType.COMMUNICATE -> listOf(requirement("communication.dispatch", inputs = setOf("goal-photon"), outputs = setOf("delivery-receipt")))
            IntentType.STORE_OR_REMEMBER -> listOf(requirement("memory.store", inputs = setOf("goal-photon"), outputs = setOf("memory-photon")))
            IntentType.UNKNOWN -> emptyList()
        }
        val languageBlocking = goal.intent == IntentType.UNKNOWN || goal.ambiguities.any { it.severity >= 0.90 }
        return GoalCapabilityPlan(goal, requirements, languageBlocking)
    }

    private fun requirement(
        id: String,
        inputs: Set<String> = emptySet(),
        outputs: Set<String> = emptySet(),
    ) = CapabilityRequirement(
        capabilityId = CapabilityId(id),
        severity = GapSeverity.BLOCKING,
        requiredInputs = inputs,
        requiredOutputs = outputs,
    )
}

/**
 * Routes every language goal through the single mutable runtime CapabilityRegistry. Local system
 * executors are composed into that registry by the kernel just like other APK-shipped modules;
 * this router never keeps a second provider catalog and therefore cannot bypass registry state.
 */
class LanguageGoalCapabilityRouter(
    private val registry: CapabilityRegistry,
    private val mapper: LanguageGoalCapabilityMapper = LanguageGoalCapabilityMapper(),
    private val gapDetector: CapabilityGapDetector = CapabilityGapDetector(registry),
) {
    suspend fun route(goal: GoalFrame): GoalCapabilityResolution {
        val plan = mapper.plan(goal)
        val selected = linkedMapOf<CapabilityId, CapabilityDescriptor>()
        val gaps = mutableListOf<CapabilityGap>()
        for (requirement in plan.requirements) {
            val gap = gapDetector.detect(requirement)
            if (gap != null) {
                gaps += gap
                continue
            }
            registry.providersFor(requirement.capabilityId)
                .firstOrNull { provider -> providerSatisfies(requirement, provider) }
                ?.let { selected[requirement.capabilityId] = it }
                ?: gaps.add(
                    CapabilityGap(
                        requirement = requirement,
                        type = CapabilityGapType.CONTRACT_MISMATCH,
                    )
                )
        }
        return GoalCapabilityResolution(plan, selected, gaps)
    }

    private fun providerSatisfies(
        requirement: CapabilityRequirement,
        provider: CapabilityDescriptor,
    ): Boolean =
        requirement.requiredInputs.containsAll(provider.contract.requiredInputs) &&
            provider.contract.outputs.containsAll(requirement.requiredOutputs)

    companion object {
        /** APK-shipped local executors. The kernel installs these into the shared registry. */
        val LOCAL_SYSTEM_PROVIDERS: List<CapabilityDescriptor> = listOf(
            CapabilityDescriptor(
                capabilityId = CapabilityId("goal.resume"),
                providerId = "local-goal-resume-core",
                providerType = ProviderType.MODULE,
                contract = CapabilityContract(
                    requiredInputs = setOf("goal-photon"),
                    outputs = setOf("goal-photon"),
                ),
                state = ProviderState.ACTIVE,
                trustLevel = TrustLevel.SYSTEM,
                reliability = 1.0,
                cost = 0.0,
            ),
            CapabilityDescriptor(
                capabilityId = CapabilityId("image.transform.mmsi"),
                providerId = "local-image-transform-core",
                providerType = ProviderType.MODULE,
                contract = CapabilityContract(
                    requiredInputs = setOf("image-photon"),
                    outputs = setOf("image-photon"),
                ),
                state = ProviderState.ACTIVE,
                trustLevel = TrustLevel.SYSTEM,
                reliability = 1.0,
                cost = 0.0,
            ),
            CapabilityDescriptor(
                capabilityId = CapabilityId("deepsearch.query"),
                providerId = "local-deepsearch-core",
                providerType = ProviderType.MODULE,
                contract = CapabilityContract(
                    requiredInputs = emptySet(),
                    outputs = setOf("reference-photons"),
                ),
                state = ProviderState.ACTIVE,
                trustLevel = TrustLevel.SYSTEM,
                reliability = 1.0,
                cost = 0.0,
            ),
            CapabilityDescriptor(
                capabilityId = CapabilityId("knowledge.resolve"),
                providerId = "local-knowledge-core",
                providerType = ProviderType.MODULE,
                contract = CapabilityContract(
                    requiredInputs = setOf("goal-photon"),
                    outputs = setOf("answer-photon"),
                ),
                state = ProviderState.ACTIVE,
                trustLevel = TrustLevel.SYSTEM,
                reliability = 1.0,
                cost = 0.0,
            ),
            CapabilityDescriptor(
                capabilityId = CapabilityId("planner.schedule"),
                providerId = "local-reminder-core",
                providerType = ProviderType.MODULE,
                contract = CapabilityContract(
                    requiredInputs = setOf("goal-photon"),
                    outputs = setOf("scheduled-action"),
                ),
                state = ProviderState.ACTIVE,
                trustLevel = TrustLevel.SYSTEM,
                reliability = 1.0,
                cost = 0.0,
            ),
            CapabilityDescriptor(
                capabilityId = CapabilityId("communication.dispatch"),
                providerId = "local-share-core",
                providerType = ProviderType.MODULE,
                contract = CapabilityContract(
                    requiredInputs = setOf("goal-photon"),
                    outputs = setOf("delivery-receipt"),
                ),
                state = ProviderState.ACTIVE,
                trustLevel = TrustLevel.SYSTEM,
                reliability = 1.0,
                cost = 0.0,
            ),
            CapabilityDescriptor(
                capabilityId = CapabilityId("memory.store"),
                providerId = "local-memory-core",
                providerType = ProviderType.MODULE,
                contract = CapabilityContract(
                    requiredInputs = setOf("goal-photon"),
                    outputs = setOf("memory-photon"),
                ),
                state = ProviderState.ACTIVE,
                trustLevel = TrustLevel.SYSTEM,
                reliability = 1.0,
                cost = 0.0,
            ),
        )
    }
}
