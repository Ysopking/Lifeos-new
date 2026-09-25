package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds

enum class MetaRealizationCycleState {
    STATE_FROZEN,
    PREDICTIVE_STATE_READY,
    IDENTIFIABILITY_READY,
    INFORMATION_REQUIRED,
    AWAITING_OBSERVATION,
    OUTCOME_OBSERVED,
    REBASED,
    UNRESOLVED,
}

data class MetaRealizationCycle private constructor(
    val cycleId: String,
    val state: MetaRealizationCycleState,
    val realizationProfileFingerprint: String,
    val sourceRealizationStateId: String,
    val sourceRealizationRevisionId: String,
    val predictiveStateId: String?,
    val identifiabilityFingerprint: String?,
    val informationPlanFingerprint: String?,
    val observationFingerprint: String?,
    val successorRealizationRevisionId: String?,
    val transitionFingerprint: String?,
    val unresolvedReason: String?,
    val predecessorCycleFingerprint: String?,
    val fingerprint: String,
) {
    init {
        require(realizationProfileFingerprint.isNotBlank())
        require(sourceRealizationStateId.isNotBlank())
        require(sourceRealizationRevisionId.isNotBlank())
        require(cycleId == expectedCycleId(
            realizationProfileFingerprint,
            sourceRealizationRevisionId,
        ))
        require(
            fingerprint == expectedFingerprint(
                cycleId = cycleId,
                state = state,
                realizationProfileFingerprint = realizationProfileFingerprint,
                sourceRealizationStateId = sourceRealizationStateId,
                sourceRealizationRevisionId = sourceRealizationRevisionId,
                predictiveStateId = predictiveStateId,
                identifiabilityFingerprint = identifiabilityFingerprint,
                informationPlanFingerprint = informationPlanFingerprint,
                observationFingerprint = observationFingerprint,
                successorRealizationRevisionId = successorRealizationRevisionId,
                transitionFingerprint = transitionFingerprint,
                unresolvedReason = unresolvedReason,
                predecessorCycleFingerprint = predecessorCycleFingerprint,
            )
        )
        validateStateShape()
    }

    val truthAuthority: Boolean
        get() = false

    val executionAuthority: Boolean
        get() = false

    val directWorldMutationAllowed: Boolean
        get() = false

    private fun validateStateShape() {
        when (state) {
            MetaRealizationCycleState.STATE_FROZEN -> {
                require(predictiveStateId == null)
                require(identifiabilityFingerprint == null)
                require(informationPlanFingerprint == null)
                require(observationFingerprint == null)
                require(successorRealizationRevisionId == null)
                require(transitionFingerprint == null)
                require(unresolvedReason == null)
            }

            MetaRealizationCycleState.PREDICTIVE_STATE_READY -> {
                require(!predictiveStateId.isNullOrBlank())
                require(identifiabilityFingerprint == null)
                require(informationPlanFingerprint == null)
                require(observationFingerprint == null)
                require(successorRealizationRevisionId == null)
                require(transitionFingerprint == null)
                require(unresolvedReason == null)
            }

            MetaRealizationCycleState.IDENTIFIABILITY_READY -> {
                require(!predictiveStateId.isNullOrBlank())
                require(!identifiabilityFingerprint.isNullOrBlank())
                require(informationPlanFingerprint == null)
                require(observationFingerprint == null)
                require(successorRealizationRevisionId == null)
                require(transitionFingerprint == null)
                require(unresolvedReason == null)
            }

            MetaRealizationCycleState.INFORMATION_REQUIRED -> {
                require(!predictiveStateId.isNullOrBlank())
                require(!identifiabilityFingerprint.isNullOrBlank())
                require(informationPlanFingerprint == null)
                require(observationFingerprint == null)
                require(successorRealizationRevisionId == null)
                require(transitionFingerprint == null)
                require(unresolvedReason == null)
            }

            MetaRealizationCycleState.AWAITING_OBSERVATION -> {
                require(!predictiveStateId.isNullOrBlank())
                require(!identifiabilityFingerprint.isNullOrBlank())
                require(!informationPlanFingerprint.isNullOrBlank())
                require(observationFingerprint == null)
                require(successorRealizationRevisionId == null)
                require(transitionFingerprint == null)
                require(unresolvedReason == null)
            }

            MetaRealizationCycleState.OUTCOME_OBSERVED -> {
                require(!predictiveStateId.isNullOrBlank())
                require(!identifiabilityFingerprint.isNullOrBlank())
                require(!observationFingerprint.isNullOrBlank())
                require(successorRealizationRevisionId == null)
                require(transitionFingerprint == null)
                require(unresolvedReason == null)
            }

            MetaRealizationCycleState.REBASED -> {
                require(!predictiveStateId.isNullOrBlank())
                require(!identifiabilityFingerprint.isNullOrBlank())
                require(!observationFingerprint.isNullOrBlank())
                require(!successorRealizationRevisionId.isNullOrBlank())
                require(!transitionFingerprint.isNullOrBlank())
                require(unresolvedReason == null)
            }

            MetaRealizationCycleState.UNRESOLVED -> {
                require(!unresolvedReason.isNullOrBlank())
                require(successorRealizationRevisionId == null)
                require(transitionFingerprint == null)
            }
        }
    }

    companion object {
        fun start(
            source: CanonicalRealizationState,
            realizationProfileFingerprint: String,
            predecessorCycleFingerprint: String? = null,
        ): MetaRealizationCycle {
            require(realizationProfileFingerprint.isNotBlank())
            return create(
                state = MetaRealizationCycleState.STATE_FROZEN,
                realizationProfileFingerprint = realizationProfileFingerprint,
                sourceRealizationStateId = source.id,
                sourceRealizationRevisionId = source.revisionId,
                predecessorCycleFingerprint = predecessorCycleFingerprint,
            )
        }

        internal fun create(
            state: MetaRealizationCycleState,
            realizationProfileFingerprint: String,
            sourceRealizationStateId: String,
            sourceRealizationRevisionId: String,
            predictiveStateId: String? = null,
            identifiabilityFingerprint: String? = null,
            informationPlanFingerprint: String? = null,
            observationFingerprint: String? = null,
            successorRealizationRevisionId: String? = null,
            transitionFingerprint: String? = null,
            unresolvedReason: String? = null,
            predecessorCycleFingerprint: String? = null,
        ): MetaRealizationCycle {
            val cycleId = expectedCycleId(
                realizationProfileFingerprint,
                sourceRealizationRevisionId,
            )
            return MetaRealizationCycle(
                cycleId = cycleId,
                state = state,
                realizationProfileFingerprint = realizationProfileFingerprint,
                sourceRealizationStateId = sourceRealizationStateId,
                sourceRealizationRevisionId = sourceRealizationRevisionId,
                predictiveStateId = predictiveStateId,
                identifiabilityFingerprint = identifiabilityFingerprint,
                informationPlanFingerprint = informationPlanFingerprint,
                observationFingerprint = observationFingerprint,
                successorRealizationRevisionId = successorRealizationRevisionId,
                transitionFingerprint = transitionFingerprint,
                unresolvedReason = unresolvedReason,
                predecessorCycleFingerprint = predecessorCycleFingerprint,
                fingerprint = expectedFingerprint(
                    cycleId = cycleId,
                    state = state,
                    realizationProfileFingerprint = realizationProfileFingerprint,
                    sourceRealizationStateId = sourceRealizationStateId,
                    sourceRealizationRevisionId = sourceRealizationRevisionId,
                    predictiveStateId = predictiveStateId,
                    identifiabilityFingerprint = identifiabilityFingerprint,
                    informationPlanFingerprint = informationPlanFingerprint,
                    observationFingerprint = observationFingerprint,
                    successorRealizationRevisionId = successorRealizationRevisionId,
                    transitionFingerprint = transitionFingerprint,
                    unresolvedReason = unresolvedReason,
                    predecessorCycleFingerprint = predecessorCycleFingerprint,
                ),
            )
        }

        private fun expectedCycleId(
            realizationProfileFingerprint: String,
            sourceRealizationRevisionId: String,
        ): String = "meta-realization-cycle:" + StableFieldIds.fingerprint(
            "meta-realization-cycle-id/v1",
            realizationProfileFingerprint,
            sourceRealizationRevisionId,
        )

        private fun expectedFingerprint(
            cycleId: String,
            state: MetaRealizationCycleState,
            realizationProfileFingerprint: String,
            sourceRealizationStateId: String,
            sourceRealizationRevisionId: String,
            predictiveStateId: String?,
            identifiabilityFingerprint: String?,
            informationPlanFingerprint: String?,
            observationFingerprint: String?,
            successorRealizationRevisionId: String?,
            transitionFingerprint: String?,
            unresolvedReason: String?,
            predecessorCycleFingerprint: String?,
        ): String = StableFieldIds.fingerprint(
            "meta-realization-cycle/v1",
            cycleId,
            state.name,
            realizationProfileFingerprint,
            sourceRealizationStateId,
            sourceRealizationRevisionId,
            predictiveStateId.orEmpty(),
            identifiabilityFingerprint.orEmpty(),
            informationPlanFingerprint.orEmpty(),
            observationFingerprint.orEmpty(),
            successorRealizationRevisionId.orEmpty(),
            transitionFingerprint.orEmpty(),
            unresolvedReason.orEmpty(),
            predecessorCycleFingerprint.orEmpty(),
        )
    }
}

/**
 * B525 M9 transition coordinator.
 *
 * It only advances immutable epistemic cycle state. Execution remains delegated to existing
 * evidence-action, owner-policy and effect-authority layers.
 */
class MetaRealizationCycleCoordinator {
    fun recordPredictiveState(
        cycle: MetaRealizationCycle,
        predictiveState: PredictiveStateClass,
    ): MetaRealizationCycle {
        require(cycle.state == MetaRealizationCycleState.STATE_FROZEN)
        require(
            predictiveState.realizationProfileFingerprint ==
                cycle.realizationProfileFingerprint
        )
        return transition(
            cycle = cycle,
            state = MetaRealizationCycleState.PREDICTIVE_STATE_READY,
            predictiveStateId = predictiveState.id,
        )
    }

    fun recordIdentifiability(
        cycle: MetaRealizationCycle,
        assessment: IdentifiabilityAssessment,
    ): MetaRealizationCycle {
        require(cycle.state == MetaRealizationCycleState.PREDICTIVE_STATE_READY)
        require(
            assessment.realizationProfileFingerprint ==
                cycle.realizationProfileFingerprint
        )
        return transition(
            cycle = cycle,
            state = MetaRealizationCycleState.IDENTIFIABILITY_READY,
            identifiabilityFingerprint = assessment.fingerprint,
        )
    }

    fun requireInformation(
        cycle: MetaRealizationCycle,
    ): MetaRealizationCycle {
        require(cycle.state == MetaRealizationCycleState.IDENTIFIABILITY_READY)
        return transition(
            cycle = cycle,
            state = MetaRealizationCycleState.INFORMATION_REQUIRED,
        )
    }

    fun attachInformationPlan(
        cycle: MetaRealizationCycle,
        plan: InformationActionPlan,
    ): MetaRealizationCycle {
        require(cycle.state == MetaRealizationCycleState.INFORMATION_REQUIRED)
        require(plan.identifiabilityFingerprint == cycle.identifiabilityFingerprint)
        require(!plan.executionAuthority)
        return transition(
            cycle = cycle,
            state = MetaRealizationCycleState.AWAITING_OBSERVATION,
            informationPlanFingerprint = plan.fingerprint,
        )
    }

    fun recordObservation(
        cycle: MetaRealizationCycle,
        observationFingerprint: String,
    ): MetaRealizationCycle {
        require(
            cycle.state == MetaRealizationCycleState.AWAITING_OBSERVATION ||
                cycle.state == MetaRealizationCycleState.IDENTIFIABILITY_READY
        )
        require(observationFingerprint.isNotBlank())
        return transition(
            cycle = cycle,
            state = MetaRealizationCycleState.OUTCOME_OBSERVED,
            observationFingerprint = observationFingerprint,
        )
    }

    fun rebase(
        cycle: MetaRealizationCycle,
        successor: CanonicalRealizationState,
    ): MetaRealizationCycle {
        require(cycle.state == MetaRealizationCycleState.OUTCOME_OBSERVED)
        require(successor.predecessorRevisionId == cycle.sourceRealizationRevisionId) {
            "Successor realization must bind the cycle source revision"
        }
        val transitionFingerprint = requireNotNull(successor.transitionFingerprint) {
            "Successor realization requires transition provenance"
        }
        return transition(
            cycle = cycle,
            state = MetaRealizationCycleState.REBASED,
            successorRealizationRevisionId = successor.revisionId,
            transitionFingerprint = transitionFingerprint,
        )
    }

    fun unresolved(
        cycle: MetaRealizationCycle,
        reason: String,
    ): MetaRealizationCycle {
        require(cycle.state != MetaRealizationCycleState.REBASED)
        require(cycle.state != MetaRealizationCycleState.UNRESOLVED)
        require(reason.isNotBlank())
        return transition(
            cycle = cycle,
            state = MetaRealizationCycleState.UNRESOLVED,
            unresolvedReason = reason,
        )
    }

    private fun transition(
        cycle: MetaRealizationCycle,
        state: MetaRealizationCycleState,
        predictiveStateId: String? = cycle.predictiveStateId,
        identifiabilityFingerprint: String? = cycle.identifiabilityFingerprint,
        informationPlanFingerprint: String? = cycle.informationPlanFingerprint,
        observationFingerprint: String? = cycle.observationFingerprint,
        successorRealizationRevisionId: String? = cycle.successorRealizationRevisionId,
        transitionFingerprint: String? = cycle.transitionFingerprint,
        unresolvedReason: String? = cycle.unresolvedReason,
    ): MetaRealizationCycle = MetaRealizationCycle.create(
        state = state,
        realizationProfileFingerprint = cycle.realizationProfileFingerprint,
        sourceRealizationStateId = cycle.sourceRealizationStateId,
        sourceRealizationRevisionId = cycle.sourceRealizationRevisionId,
        predictiveStateId = predictiveStateId,
        identifiabilityFingerprint = identifiabilityFingerprint,
        informationPlanFingerprint = informationPlanFingerprint,
        observationFingerprint = observationFingerprint,
        successorRealizationRevisionId = successorRealizationRevisionId,
        transitionFingerprint = transitionFingerprint,
        unresolvedReason = unresolvedReason,
        predecessorCycleFingerprint = cycle.predecessorCycleFingerprint,
    )
}
