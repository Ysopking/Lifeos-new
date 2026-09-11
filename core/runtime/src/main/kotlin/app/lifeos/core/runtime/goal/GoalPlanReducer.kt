package app.lifeos.core.runtime.goal

import app.lifeos.core.model.PhotonId

data class GoalPlanRuntimeState(
    val definition: GoalPlanDefinition,
    val revision: Long,
    val headTransitionId: GoalTransitionId?,
    val history: List<GoalPlanTransition>,
    val stepStates: Map<GoalStepId, GoalStepState>,
    val activeActionIds: Map<GoalStepId, String>,
    val outcomePhotonIds: Map<GoalStepId, PhotonId>,
) {
    init {
        require(revision == history.size.toLong()) { "Goal runtime revision/history mismatch" }
        require(headTransitionId == history.lastOrNull()?.id) { "Goal runtime head/history mismatch" }
        require(stepStates.keys == definition.steps.mapTo(mutableSetOf()) { it.id }) {
            "Goal runtime state must cover exactly the plan steps"
        }
        require(activeActionIds.keys.all(stepStates::containsKey))
        require(outcomePhotonIds.keys.all(stepStates::containsKey))
    }

    companion object {
        fun initial(definition: GoalPlanDefinition): GoalPlanRuntimeState = GoalPlanRuntimeState(
            definition = definition,
            revision = 0L,
            headTransitionId = null,
            history = emptyList(),
            stepStates = definition.steps.associate { it.id to GoalStepState.PLANNED },
            activeActionIds = emptyMap(),
            outcomePhotonIds = emptyMap(),
        )
    }
}

data class GoalTransitionApplyResult(
    val state: GoalPlanRuntimeState,
    val replayed: Boolean,
)

/** Pure append-only V7 reducer. Physical storage order is irrelevant; predecessor lineage is truth. */
class GoalPlanReducer {
    fun apply(
        state: GoalPlanRuntimeState,
        transition: GoalPlanTransition,
    ): GoalTransitionApplyResult {
        state.history.firstOrNull { it.id == transition.id }?.let { existing ->
            require(existing == transition) { "Goal transition identity collision" }
            return GoalTransitionApplyResult(state, replayed = true)
        }
        require(transition.planId == state.definition.id) { "Goal transition plan mismatch" }
        require(transition.predecessorId == state.headTransitionId) {
            "Goal transition predecessor does not match plan head"
        }
        val current = state.stepStates[transition.stepId]
            ?: error("Goal transition references unknown step")
        require(current == transition.fromState) {
            "Goal transition from-state does not match replay state"
        }
        require(isAllowed(current, transition.toState)) {
            "Illegal goal step transition: $current -> ${transition.toState}"
        }

        val nextStates = state.stepStates.toMutableMap().apply {
            put(transition.stepId, transition.toState)
        }
        val nextActions = state.activeActionIds.toMutableMap()
        val nextOutcomes = state.outcomePhotonIds.toMutableMap()
        when (transition.toState) {
            GoalStepState.RUNNING -> {
                check(transition.stepId !in nextActions) { "Goal step already has an active action" }
                nextActions[transition.stepId] = requireNotNull(transition.actionId)
            }
            GoalStepState.COMPLETED,
            GoalStepState.FAILED -> {
                val activeAction = nextActions[transition.stepId]
                    ?: error("Terminal goal transition requires an active action")
                require(activeAction == transition.actionId) {
                    "Goal outcome action does not match the active step action"
                }
                nextActions.remove(transition.stepId)
                nextOutcomes[transition.stepId] = requireNotNull(transition.outcomePhotonId)
            }
            else -> Unit
        }

        val history = state.history + transition
        return GoalTransitionApplyResult(
            state = GoalPlanRuntimeState(
                definition = state.definition,
                revision = history.size.toLong(),
                headTransitionId = transition.id,
                history = history,
                stepStates = nextStates.toMap(),
                activeActionIds = nextActions.toMap(),
                outcomePhotonIds = nextOutcomes.toMap(),
            ),
            replayed = false,
        )
    }

    fun replay(
        definition: GoalPlanDefinition,
        transitions: List<GoalPlanTransition>,
    ): GoalPlanRuntimeState {
        require(transitions.map { it.id }.distinct().size == transitions.size) {
            "Goal transition replay cannot contain duplicate physical events"
        }
        var state = GoalPlanRuntimeState.initial(definition)
        val remaining = transitions.toMutableList()
        while (remaining.isNotEmpty()) {
            val ready = remaining.filter { it.predecessorId == state.headTransitionId }
            require(ready.size == 1) {
                if (ready.isEmpty()) {
                    "Goal transition history has a missing/cyclic predecessor"
                } else {
                    "Goal transition history forks from ${state.headTransitionId}"
                }
            }
            val next = ready.single()
            state = apply(state, next).state
            remaining.remove(next)
        }
        return state
    }

    private fun isAllowed(from: GoalStepState, to: GoalStepState): Boolean = when (from) {
        GoalStepState.PLANNED -> to in setOf(
            GoalStepState.READY,
            GoalStepState.BLOCKED,
            GoalStepState.WAITING_EVIDENCE,
            GoalStepState.WAITING_CAPABILITY,
            GoalStepState.PAUSED,
            GoalStepState.CANCELLED,
            GoalStepState.REPLAN_REQUIRED,
        )
        GoalStepState.READY -> to in setOf(
            GoalStepState.RUNNING,
            GoalStepState.BLOCKED,
            GoalStepState.WAITING_EVIDENCE,
            GoalStepState.WAITING_CAPABILITY,
            GoalStepState.PAUSED,
            GoalStepState.CANCELLED,
            GoalStepState.REPLAN_REQUIRED,
        )
        GoalStepState.RUNNING -> to in setOf(
            GoalStepState.COMPLETED,
            GoalStepState.FAILED,
        )
        GoalStepState.BLOCKED,
        GoalStepState.WAITING_EVIDENCE,
        GoalStepState.WAITING_CAPABILITY,
        GoalStepState.PAUSED -> to in setOf(
            GoalStepState.READY,
            GoalStepState.BLOCKED,
            GoalStepState.WAITING_EVIDENCE,
            GoalStepState.WAITING_CAPABILITY,
            GoalStepState.PAUSED,
            GoalStepState.CANCELLED,
            GoalStepState.REPLAN_REQUIRED,
        ) && to != from
        GoalStepState.FAILED -> to in setOf(
            GoalStepState.REPLAN_REQUIRED,
            GoalStepState.CANCELLED,
        )
        GoalStepState.COMPLETED,
        GoalStepState.CANCELLED,
        GoalStepState.REPLAN_REQUIRED -> false
    }
}
