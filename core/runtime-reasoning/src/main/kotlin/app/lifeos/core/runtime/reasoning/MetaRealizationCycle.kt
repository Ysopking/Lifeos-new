package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds

enum class MetaRealizationCycleState {
    STATE_FROZEN,
    PREDICTIVE_STATE_READY,
    INFORMATION_REQUIRED,
    AWAITING_OBSERVATION,
    OUTCOME_OBSERVED,
    REBASED,
    UNRESOLVED,
}

data class MetaRealizationCycle private constructor(
    val cycleId: String,
    val state: MetaRealizationCycleState,
    val sourceRevisionId: String,
    val realizationProfileFingerprint: String,
    val predictiveQuotientFingerprint: String?,
    val identifiabilityFingerprint: String?,
    val informationPlanFingerprint: String?,
    val observationFingerprint: String?,
    val successorRevisionId: String?,
    val transitionFingerprint: String?,
    val predecessorCycleFingerprint: String?,
    val fingerprint: String,
) {
    init {
        require(sourceRevisionId.isNotBlank())
        require(realizationProfileFingerprint.isNotBlank())
        require(predecessorCycleFingerprint?.isNotBlank() != false)
        when (state) {
            MetaRealizationCycleState.STATE_FROZEN -> {
                require(predictiveQuotientFingerprint == null)
                require(identifiabilityFingerprint == null)
                require(informationPlanFingerprint == null)
                require(observationFingerprint == null)
                require(successorRevisionId == null)
                require(transitionFingerprint == null)
            }
            MetaRealizationCycleState.PREDICTIVE_STATE_READY -> {
                require(!predictiveQuotientFingerprint.isNullOrBlank())
                require(identifiabilityFingerprint == null)
                require(informationPlanFingerprint == null)
                require(observationFingerprint == null)
                require(successorRevisionId == null)
                require(transitionFingerprint == null)
            }
            MetaRealizationCycleState.INFORMATION_REQUIRED,
            MetaRealizationCycleState.UNRESOLVED -> {
                require(!predictiveQuotientFingerprint.isNullOrBlank())
                require(!identifiabilityFingerprint.isNullOrBlank())
                require(informationPlanFingerprint == null)
                require(observationFingerprint == null)
                require(successorRevisionId == null)
                require(transitionFingerprint == null)
            }
            MetaRealizationCycleState.AWAITING_OBSERVATION -> {
                require(!predictiveQuotientFingerprint.isNullOrBlank())
                require(!identifiabilityFingerprint.isNullOrBlank())
                require(!informationPlanFingerprint.isNullOrBlank())
                require(observationFingerprint == null)
                require(successorRevisionId == null)
                require(transitionFingerprint == null)
            }
            MetaRealizationCycleState.OUTCOME_OBSERVED -> {
                require(!predictiveQuotientFingerprint.isNullOrBlank())
                require(!identifiabilityFingerprint.isNullOrBlank())
                require(!informationPlanFingerprint.isNullOrBlank())
                require(!observationFingerprint.isNullOrBlank())
                require(successorRevisionId == null)
                require(transitionFingerprint == null)
            }
            MetaRealizationCycleState.REBASED -> {
                require(!predictiveQuotientFingerprint.isNullOrBlank())
                require(!identifiabilityFingerprint.isNullOrBlank())
                require(!informationPlanFingerprint.isNullOrBlank())
                require(!observationFingerprint.isNullOrBlank())
                require(!successorRevisionId.isNullOrBlank())
                require(!transitionFingerprint.isNullOrBlank())
                require(successorRevisionId != sourceRevisionId)
            }
        }
        require(
            fingerprint == expectedFingerprint(
                state,
                sourceRevisionId,
                realizationProfileFingerprint,
                predictiveQuotientFingerprint,
                identifiabilityFingerprint,
                informationPlanFingerprint,
                observationFingerprint,
                successorRevisionId,
                transitionFingerprint,
                predecessorCycleFingerprint,
            )
        )
        require(cycleId == "meta-realization-cycle:$fingerprint")
    }

    val executionAuthority: Boolean
        get() = false

    val directWorldMutationAllowed: Boolean
        get() = false

    fun predictiveReady(
        predictiveQuotientFingerprint: String,
    ): MetaRealizationCycle {
        require(state == MetaRealizationCycleState.STATE_FROZEN)
        require(predictiveQuotientFingerprint.isNotBlank())
        return create(
            state = MetaRealizationCycleState.PREDICTIVE_STATE_READY,
            predictiveQuotientFingerprint = predictiveQuotientFingerprint,
        )
    }

    fun assessIdentifiability(
        identifiability: IdentifiabilityAssessment,
    ): MetaRealizationCycle {
        require(state == MetaRealizationCycleState.PREDICTIVE_STATE_READY)
        require(identifiability.realizationProfileFingerprint == realizationProfileFingerprint)
        val nextState = if (
            identifiability.status == IdentifiabilityStatus.OBSERVATIONALLY_DISTINCT
        ) {
            MetaRealizationCycleState.UNRESOLVED
        } else {
            MetaRealizationCycleState.INFORMATION_REQUIRED
        }
        return create(
            state = nextState,
            predictiveQuotientFingerprint = predictiveQuotientFingerprint,
            identifiabilityFingerprint = identifiability.fingerprint,
        )
    }

    fun awaitObservation(
        informationPlan: InformationActionPlan,
    ): MetaRealizationCycle {
        require(state == MetaRealizationCycleState.INFORMATION_REQUIRED)
        require(informationPlan.identifiabilityFingerprint == identifiabilityFingerprint)
        require(informationPlan.items.isNotEmpty()) {
            "Awaiting observation requires at least one planned information action"
        }
        return create(
            state = MetaRealizationCycleState.AWAITING_OBSERVATION,
            predictiveQuotientFingerprint = predictiveQuotientFingerprint,
            identifiabilityFingerprint = identifiabilityFingerprint,
            informationPlanFingerprint = informationPlan.fingerprint,
        )
    }

    fun observe(
        observationFingerprint: String,
    ): MetaRealizationCycle {
        require(state == MetaRealizationCycleState.AWAITING_OBSERVATION)
        require(observationFingerprint.isNotBlank())
        return create(
            state = MetaRealizationCycleState.OUTCOME_OBSERVED,
            predictiveQuotientFingerprint = predictiveQuotientFingerprint,
            identifiabilityFingerprint = identifiabilityFingerprint,
            informationPlanFingerprint = informationPlanFingerprint,
            observationFingerprint = observationFingerprint,
        )
    }

    fun rebase(
        successor: CanonicalRealizationState,
        transitionFingerprint: String,
    ): MetaRealizationCycle {
        require(state == MetaRealizationCycleState.OUTCOME_OBSERVED)
        require(transitionFingerprint.isNotBlank())
        require(successor.predecessorRevisionId == sourceRevisionId) {
            "Rebased realization must bind the source revision"
        }
        require(successor.transitionFingerprint == transitionFingerprint) {
            "Successor realization transition must match cycle transition"
        }
        return create(
            state = MetaRealizationCycleState.REBASED,
            predictiveQuotientFingerprint = predictiveQuotientFingerprint,
            identifiabilityFingerprint = identifiabilityFingerprint,
            informationPlanFingerprint = informationPlanFingerprint,
            observationFingerprint = observationFingerprint,
            successorRevisionId = successor.revisionId,
            transitionFingerprint = transitionFingerprint,
        )
    }

    private fun create(
        state: MetaRealizationCycleState,
        predictiveQuotientFingerprint: String? = null,
        identifiabilityFingerprint: String? = null,
        informationPlanFingerprint: String? = null,
        observationFingerprint: String? = null,
        successorRevisionId: String? = null,
        transitionFingerprint: String? = null,
    ): MetaRealizationCycle = create(
        state = state,
        sourceRevisionId = sourceRevisionId,
        realizationProfileFingerprint = realizationProfileFingerprint,
        predictiveQuotientFingerprint = predictiveQuotientFingerprint,
        identifiabilityFingerprint = identifiabilityFingerprint,
        informationPlanFingerprint = informationPlanFingerprint,
        observationFingerprint = observationFingerprint,
        successorRevisionId = successorRevisionId,
        transitionFingerprint = transitionFingerprint,
        predecessorCycleFingerprint = fingerprint,
    )

    companion object {
        fun start(
            source: CanonicalRealizationState,
            profile: RealizationTransferProfile,
        ): MetaRealizationCycle {
            require(
                profile.requiredComponents.all { required ->
                    source.components.any { it.kind == required }
                }
            ) {
                "Frozen realization state does not satisfy transfer-profile component contract"
            }
            return create(
                state = MetaRealizationCycleState.STATE_FROZEN,
                sourceRevisionId = source.revisionId,
                realizationProfileFingerprint = profile.fingerprint,
                predictiveQuotientFingerprint = null,
                identifiabilityFingerprint = null,
                informationPlanFingerprint = null,
                observationFingerprint = null,
                successorRevisionId = null,
                transitionFingerprint = null,
                predecessorCycleFingerprint = null,
            )
        }

        private fun create(
            state: MetaRealizationCycleState,
            sourceRevisionId: String,
            realizationProfileFingerprint: String,
            predictiveQuotientFingerprint: String?,
            identifiabilityFingerprint: String?,
            informationPlanFingerprint: String?,
            observationFingerprint: String?,
            successorRevisionId: String?,
            transitionFingerprint: String?,
            predecessorCycleFingerprint: String?,
        ): MetaRealizationCycle {
            val fp = expectedFingerprint(
                state,
                sourceRevisionId,
                realizationProfileFingerprint,
                predictiveQuotientFingerprint,
                identifiabilityFingerprint,
                informationPlanFingerprint,
                observationFingerprint,
                successorRevisionId,
                transitionFingerprint,
                predecessorCycleFingerprint,
            )
            return MetaRealizationCycle(
                cycleId = "meta-realization-cycle:$fp",
                state = state,
                sourceRevisionId = sourceRevisionId,
                realizationProfileFingerprint = realizationProfileFingerprint,
                predictiveQuotientFingerprint = predictiveQuotientFingerprint,
                identifiabilityFingerprint = identifiabilityFingerprint,
                informationPlanFingerprint = informationPlanFingerprint,
                observationFingerprint = observationFingerprint,
                successorRevisionId = successorRevisionId,
                transitionFingerprint = transitionFingerprint,
                predecessorCycleFingerprint = predecessorCycleFingerprint,
                fingerprint = fp,
            )
        }

        private fun expectedFingerprint(
            state: MetaRealizationCycleState,
            sourceRevisionId: String,
            realizationProfileFingerprint: String,
            predictiveQuotientFingerprint: String?,
            identifiabilityFingerprint: String?,
            informationPlanFingerprint: String?,
            observationFingerprint: String?,
            successorRevisionId: String?,
            transitionFingerprint: String?,
            predecessorCycleFingerprint: String?,
        ): String = StableFieldIds.fingerprint(
            "meta-realization-cycle/v1",
            state.name,
            sourceRevisionId,
            realizationProfileFingerprint,
            predictiveQuotientFingerprint.orEmpty(),
            identifiabilityFingerprint.orEmpty(),
            informationPlanFingerprint.orEmpty(),
            observationFingerprint.orEmpty(),
            successorRevisionId.orEmpty(),
            transitionFingerprint.orEmpty(),
            predecessorCycleFingerprint.orEmpty(),
        )
    }
}
