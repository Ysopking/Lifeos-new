package app.lifeos.core.runtime.goal

import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType
import app.lifeos.core.model.Photon
import app.lifeos.core.runtime.capability.GoalCapabilityResolution
import java.time.Instant

/** Deterministically turns an authoritative Goal-Photon into the first durable V7 plan. */
class GoalPlanFactory {
    fun create(
        goalPhoton: Photon,
        goal: GoalFrame,
        routing: GoalCapabilityResolution,
        createdAt: Instant = goalPhoton.provenance.createdAt,
    ): GoalPlanDefinition {
        require("goal" in goalPhoton.tags) { "Goal plan source must be a Goal-Photon" }
        require(goalPhoton.revision > 0L) { "Goal-Photon revision must be positive" }

        val clarificationNeeded = routing.plan.languageBlocking || goal.ambiguities.any { it.severity >= CLARIFICATION_SEVERITY }
        val capabilityNeeded = routing.blockingGaps.isNotEmpty()
        val executeObjective = executeObjective(goal)

        val specs = buildList {
            if (clarificationNeeded) {
                add(
                    GoalStepSpec(
                        key = "clarify-goal",
                        objective = "Clarify unresolved language or reference ambiguity before acting on: ${goal.objective}",
                        priority = 1_000,
                    )
                )
            }
            if (capabilityNeeded) {
                add(
                    GoalStepSpec(
                        key = "resolve-capability",
                        objective = routing.blockingGaps
                            .map { gap -> "Resolve capability ${gap.requirement.capabilityId.value} (${gap.type.name})" }
                            .distinct()
                            .sorted()
                            .joinToString("; "),
                        dependencyKeys = if (clarificationNeeded) setOf("clarify-goal") else emptySet(),
                        priority = 800,
                    )
                )
            }
            add(
                GoalStepSpec(
                    key = executionKey(goal.intent),
                    objective = executeObjective,
                    dependencyKeys = buildSet {
                        if (clarificationNeeded) add("clarify-goal")
                        if (capabilityNeeded) add("resolve-capability")
                    },
                    priority = 500,
                )
            )
            add(
                GoalStepSpec(
                    key = "record-outcome",
                    objective = "Persist the result, evidence, and learning outcome for: ${goal.objective}",
                    dependencyKeys = setOf(executionKey(goal.intent)),
                    priority = 100,
                )
            )
        }

        return GoalPlanDefinition.create(
            sourceGoalPhotonId = goalPhoton.id,
            sourceGoalPhotonRevision = goalPhoton.revision,
            planRevision = 1L,
            stepSpecs = specs,
            createdAt = createdAt,
        )
    }

    private fun executionKey(intent: IntentType): String = when (intent) {
        IntentType.CONTINUE -> "resume-goal"
        IntentType.CREATE_IMAGE -> "create-image"
        IntentType.TRANSFORM_IMAGE -> "transform-image"
        IntentType.SEARCH -> "search-evidence"
        IntentType.BUILD_OR_IMPLEMENT -> "implement-build"
        IntentType.QUERY -> "resolve-query"
        IntentType.SCHEDULE -> "schedule-action"
        IntentType.COMMUNICATE -> "prepare-communication"
        IntentType.STORE_OR_REMEMBER -> "store-memory"
        IntentType.UNKNOWN -> "resolve-unknown-intent"
    }

    private fun executeObjective(goal: GoalFrame): String = when (goal.intent) {
        IntentType.CONTINUE -> "Resume the referenced durable goal: ${goal.objective}"
        IntentType.CREATE_IMAGE -> "Create an image result for: ${goal.objective}"
        IntentType.TRANSFORM_IMAGE -> "Transform the referenced image for: ${goal.objective}"
        IntentType.SEARCH -> "Search and collect evidence for: ${goal.objective}"
        IntentType.BUILD_OR_IMPLEMENT -> "Implement and verify: ${goal.objective}"
        IntentType.QUERY -> "Resolve the question using available knowledge: ${goal.objective}"
        IntentType.SCHEDULE -> "Create the requested scheduled action for: ${goal.objective}"
        IntentType.COMMUNICATE -> "Prepare the requested communication for owner handoff: ${goal.objective}"
        IntentType.STORE_OR_REMEMBER -> "Persist the requested memory: ${goal.objective}"
        IntentType.UNKNOWN -> "Resolve unknown intent before action: ${goal.objective}"
    }

    private companion object {
        const val CLARIFICATION_SEVERITY = 0.90
    }
}
