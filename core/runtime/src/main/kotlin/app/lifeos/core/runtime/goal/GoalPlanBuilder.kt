package app.lifeos.core.runtime.goal

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType
import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.LanguageGoalCapabilityMapper
import java.time.Instant

enum class GoalStepExecutionKind {
    ACTION,
    VERIFY_OUTCOME,
}

data class GoalStepExecutionContract(
    val stepId: GoalStepId,
    val kind: GoalStepExecutionKind,
    val actionIntent: IntentType?,
    val requiredCapabilityIds: Set<CapabilityId>,
    val sourceGoalPhotonId: PhotonId,
    val sourceGoalPhotonRevision: Long,
    val fingerprint: String,
) {
    init {
        require(sourceGoalPhotonRevision > 0L)
        require(fingerprint.isNotBlank())
        when (kind) {
            GoalStepExecutionKind.ACTION -> require(actionIntent != null && actionIntent != IntentType.UNKNOWN) {
                "Action step requires a concrete intent"
            }
            GoalStepExecutionKind.VERIFY_OUTCOME -> require(actionIntent == null && requiredCapabilityIds.isEmpty()) {
                "Outcome verification is an internal step"
            }
        }
        require(fingerprint == expectedFingerprint()) { "Goal step execution contract fingerprint mismatch" }
    }

    private fun expectedFingerprint(): String = StableFieldIds.fingerprint(
        "goal-step-execution-contract/v1",
        stepId.value,
        kind.name,
        actionIntent?.name.orEmpty(),
        sourceGoalPhotonId.value,
        sourceGoalPhotonRevision.toString(),
        *requiredCapabilityIds.map { it.value }.sorted().toTypedArray(),
    )

    companion object {
        fun create(
            stepId: GoalStepId,
            kind: GoalStepExecutionKind,
            actionIntent: IntentType?,
            requiredCapabilityIds: Set<CapabilityId>,
            sourceGoalPhotonId: PhotonId,
            sourceGoalPhotonRevision: Long,
        ): GoalStepExecutionContract {
            val fingerprint = StableFieldIds.fingerprint(
                "goal-step-execution-contract/v1",
                stepId.value,
                kind.name,
                actionIntent?.name.orEmpty(),
                sourceGoalPhotonId.value,
                sourceGoalPhotonRevision.toString(),
                *requiredCapabilityIds.map { it.value }.sorted().toTypedArray(),
            )
            return GoalStepExecutionContract(
                stepId = stepId,
                kind = kind,
                actionIntent = actionIntent,
                requiredCapabilityIds = requiredCapabilityIds.toSortedSet(compareBy { it.value }),
                sourceGoalPhotonId = sourceGoalPhotonId,
                sourceGoalPhotonRevision = sourceGoalPhotonRevision,
                fingerprint = fingerprint,
            )
        }
    }
}

data class GoalPlanBlueprint(
    val definition: GoalPlanDefinition,
    val contracts: Map<GoalStepId, GoalStepExecutionContract>,
) {
    init {
        require(contracts.keys == definition.steps.mapTo(mutableSetOf()) { it.id }) {
            "Goal plan blueprint must bind every step exactly once"
        }
        require(contracts.all { (stepId, contract) -> contract.stepId == stepId })
        require(contracts.values.all {
            it.sourceGoalPhotonId == definition.sourceGoalPhotonId &&
                it.sourceGoalPhotonRevision == definition.sourceGoalPhotonRevision
        })
    }

    fun contract(stepId: GoalStepId): GoalStepExecutionContract =
        requireNotNull(contracts[stepId]) { "Unknown goal blueprint step" }
}

sealed interface GoalPlanBuildResult {
    data class Built(val blueprint: GoalPlanBlueprint) : GoalPlanBuildResult
    data class Blocked(val reason: String) : GoalPlanBuildResult {
        init { require(reason.isNotBlank()) }
    }
}

/**
 * V7-B deterministic planner. It does not invent missing authority or capabilities. Every concrete
 * goal becomes an action followed by an internal outcome-verification step. Capability requirements
 * are taken from the same mapper used by runtime routing, so planning and execution cannot drift.
 */
class GoalPlanBuilder(
    private val capabilityMapper: LanguageGoalCapabilityMapper = LanguageGoalCapabilityMapper(),
) {
    fun build(
        goal: GoalFrame,
        sourceGoalPhotonId: PhotonId,
        sourceGoalPhotonRevision: Long,
        createdAt: Instant,
        planRevision: Long = 1L,
        deadline: Instant? = null,
    ): GoalPlanBuildResult {
        require(sourceGoalPhotonRevision > 0L)
        require(planRevision > 0L)
        val capabilityPlan = capabilityMapper.plan(goal)
        if (capabilityPlan.languageBlocking) {
            return GoalPlanBuildResult.Blocked("goal-language-or-ambiguity-blocking")
        }
        if (goal.intent == IntentType.UNKNOWN) {
            return GoalPlanBuildResult.Blocked("goal-intent-unknown")
        }

        val actionKey = "action:${goal.intent.name.lowercase()}"
        val verifyKey = "verify:outcome"
        val definition = GoalPlanDefinition.create(
            sourceGoalPhotonId = sourceGoalPhotonId,
            sourceGoalPhotonRevision = sourceGoalPhotonRevision,
            planRevision = planRevision,
            stepSpecs = listOf(
                GoalStepSpec(
                    key = actionKey,
                    objective = goal.objective,
                    deadline = deadline,
                    priority = 100,
                ),
                GoalStepSpec(
                    key = verifyKey,
                    objective = "Verify persisted outcome for ${goal.intent.name.lowercase()}",
                    dependencyKeys = setOf(actionKey),
                    deadline = deadline,
                    priority = 50,
                ),
            ),
            createdAt = createdAt,
        )
        val actionStep = definition.steps.single { it.key == actionKey }
        val verifyStep = definition.steps.single { it.key == verifyKey }
        val required = capabilityPlan.requirements.mapTo(sortedSetOf(compareBy { it.value })) {
            it.capabilityId
        }
        val contracts = mapOf(
            actionStep.id to GoalStepExecutionContract.create(
                stepId = actionStep.id,
                kind = GoalStepExecutionKind.ACTION,
                actionIntent = goal.intent,
                requiredCapabilityIds = required,
                sourceGoalPhotonId = sourceGoalPhotonId,
                sourceGoalPhotonRevision = sourceGoalPhotonRevision,
            ),
            verifyStep.id to GoalStepExecutionContract.create(
                stepId = verifyStep.id,
                kind = GoalStepExecutionKind.VERIFY_OUTCOME,
                actionIntent = null,
                requiredCapabilityIds = emptySet(),
                sourceGoalPhotonId = sourceGoalPhotonId,
                sourceGoalPhotonRevision = sourceGoalPhotonRevision,
            ),
        )
        return GoalPlanBuildResult.Built(GoalPlanBlueprint(definition, contracts))
    }
}
