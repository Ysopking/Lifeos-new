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
                requirement("scene.construct.procedural", outputs = setOf("scene-graph")),
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
                .firstOrNull { provider ->
                    requirement.requiredInputs.containsAll(provider.contract.requiredInputs) &&
                        provider.contract.outputs.containsAll(requirement.requiredOutputs)
                }
                ?.let { selected[requirement.capabilityId] = it }
        }
        return GoalCapabilityResolution(plan, selected, gaps)
    }
}
