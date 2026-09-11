package app.lifeos.core.runtime.goal

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType
import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.GoalCapabilityPlan
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
 * V7-B/E deterministic planner. It does not invent missing authority or capabilities. Every concrete
 * goal becomes an action followed by an internal outcome-verification step. Capability requirements
 * are taken from the same mapper used by runtime routing, so planning and execution cannot drift.
 * On restart, execution contracts are reconstructed from the durable plan and persisted GoalFrame;
 * the durable definition itself is never silently regenerated or reshaped.
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
        val capabilityPlan = validatedCapabilityPlan(goal) ?: return blockedFor(goal)
        val actionKey = actionKey(goal)
        val verifyKey = VERIFY_KEY
        val definition = GoalPlanDefinition.create(
            sourceGoalPhotonId = sourceGoalPhotonId,
            sourceGoalPhotonRevision = sourceGoalPhotonRevision,
            planRevision = planRevision,
            stepSpecs = listOf(
                GoalStepSpec(
                    key = actionKey,
                    objective = goal.objective,
                    deadline = deadline,
                    priority = ACTION_PRIORITY,
                ),
                GoalStepSpec(
                    key = verifyKey,
                    objective = verificationObjective(goal),
                    dependencyKeys = setOf(actionKey),
                    deadline = deadline,
                    priority = VERIFY_PRIORITY,
                ),
            ),
            createdAt = createdAt,
        )
        return GoalPlanBuildResult.Built(
            blueprint(definition, goal, capabilityPlan)
        )
    }

    /**
     * Rebinds non-persisted execution contracts to an already persisted definition. Any mismatch is
     * explicit corruption/version drift and blocks execution rather than creating a replacement plan.
     */
    fun bindExisting(
        goal: GoalFrame,
        definition: GoalPlanDefinition,
    ): GoalPlanBuildResult {
        val capabilityPlan = validatedCapabilityPlan(goal) ?: return blockedFor(goal)
        val actionKey = actionKey(goal)
        val action = definition.steps.singleOrNull { it.key == actionKey }
            ?: return GoalPlanBuildResult.Blocked("persisted-plan-action-shape-mismatch")
        val verify = definition.steps.singleOrNull { it.key == VERIFY_KEY }
            ?: return GoalPlanBuildResult.Blocked("persisted-plan-verification-shape-mismatch")
        if (
            definition.steps.size != 2 ||
            action.objective != goal.objective ||
            action.priority != ACTION_PRIORITY ||
            verify.objective != verificationObjective(goal) ||
            verify.priority != VERIFY_PRIORITY ||
            verify.dependencyIds != setOf(action.id) ||
            action.dependencyIds.isNotEmpty() ||
            action.deadline != verify.deadline
        ) {
            return GoalPlanBuildResult.Blocked("persisted-plan-shape-mismatch")
        }
        return GoalPlanBuildResult.Built(
            blueprint(definition, goal, capabilityPlan)
        )
    }

    private fun blueprint(
        definition: GoalPlanDefinition,
        goal: GoalFrame,
        capabilityPlan: GoalCapabilityPlan,
    ): GoalPlanBlueprint {
        val action = definition.steps.single { it.key == actionKey(goal) }
        val verify = definition.steps.single { it.key == VERIFY_KEY }
        val required = capabilityPlan.requirements.mapTo(sortedSetOf(compareBy { it.value })) {
            it.capabilityId
        }
        return GoalPlanBlueprint(
            definition = definition,
            contracts = mapOf(
                action.id to GoalStepExecutionContract.create(
                    stepId = action.id,
                    kind = GoalStepExecutionKind.ACTION,
                    actionIntent = goal.intent,
                    requiredCapabilityIds = required,
                    sourceGoalPhotonId = definition.sourceGoalPhotonId,
                    sourceGoalPhotonRevision = definition.sourceGoalPhotonRevision,
                ),
                verify.id to GoalStepExecutionContract.create(
                    stepId = verify.id,
                    kind = GoalStepExecutionKind.VERIFY_OUTCOME,
                    actionIntent = null,
                    requiredCapabilityIds = emptySet(),
                    sourceGoalPhotonId = definition.sourceGoalPhotonId,
                    sourceGoalPhotonRevision = definition.sourceGoalPhotonRevision,
                ),
            ),
        )
    }

    private fun validatedCapabilityPlan(goal: GoalFrame): GoalCapabilityPlan? {
        val plan = capabilityMapper.plan(goal)
        return if (!plan.languageBlocking && goal.intent != IntentType.UNKNOWN) plan else null
    }

    private fun blockedFor(goal: GoalFrame): GoalPlanBuildResult.Blocked =
        if (goal.intent == IntentType.UNKNOWN) {
            GoalPlanBuildResult.Blocked("goal-intent-unknown")
        } else {
            GoalPlanBuildResult.Blocked("goal-language-or-ambiguity-blocking")
        }

    private fun actionKey(goal: GoalFrame): String = "action:${goal.intent.name.lowercase()}"

    private fun verificationObjective(goal: GoalFrame): String =
        "Verify persisted outcome for ${goal.intent.name.lowercase()}"

    private companion object {
        const val VERIFY_KEY = "verify:outcome"
        const val ACTION_PRIORITY = 100
        const val VERIFY_PRIORITY = 50
    }
}
