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
    val sourceRealizationRevisionId: String,
    val realizationProfileFingerprint: String,
    val predictiveQuotientFingerprint: String?,
    val identifiabilityFingerprint: String?,
    val informationPlanFingerprint: String?,
    val observationFingerprint: String?,
    val successorRealizationRevisionId: String?,
    val predecessorCycleFingerprint: String?,
    val fingerprint: String,
) {
    init {
        require(cycleId.isNotBlank())
        require(sourceRealizationRevisionId.isNotBlank())
        require(realizationProfileFingerprint.isNotBlank())
        predecessorCycleFingerprint?.let { require(it.isNotBlank()) }
        when (state) {
            MetaRealizationCycleState.STATE_FROZEN -> {
                require(predictiveQuotientFingerprint == null)
                require(identifiabilityFingerprint == null)
                require(informationPlanFingerprint == null)
                require(observationFingerprint == null)
                require(successorRealizationRevisionId == null)
            }
            MetaRealizationCycleState.PREDICTIVE_STATE_READY -> {
                require(!predictiveQuotientFingerprint.isNullOrBlank())
                require(informationPlanFingerprint == null)
                require(observationFingerprint == null)
                require(successorRealizationRevisionId == null)
            }
            MetaRealizationCycleState.INFORMATION_REQUIRED -> {
                require(!predictiveQuotientFingerprint.isNullOrBlank())
                require(!identifiabilityFingerprint.isNullOrBlank())
                require(informationPlanFingerprint == null)
                require(observationFingerprint == null)
                require(successorRealizationRevisionId == null)
            }
            MetaRealizationCycleState.AWAITING_OBSERVATION -> {
                require(!predictiveQuotientFingerprint.isNullOrBlank())
                require(!identifiabilityFingerprint.isNullOrBlank())
                require(!informationPlanFingerprint.isNullOrBlank())
                require(observationFingerprint == null)
                require(successorRealizationRevisionId == null)
            }
            MetaRealizationCycleState.OUTCOME_OBSERVED -> {
                require(!observationFingerprint.isNullOrBlank())
                require(successorRealizationRevisionId == null)
            }
            MetaRealizationCycleState.REBASED -> {
                require(!observationFingerprint.isNullOrBlank())
                require(!successorRealizationRevisionId.isNullOrBlank())
                require(successorRealizationRevisionId != sourceRealizationRevisionId)
            }
            MetaRealizationCycleState.UNRESOLVED -> {
                require(successorRealizationRevisionId == null)
            }
        }
        require(
            fingerprint == expectedFingerprint(
                cycleId,
                state,
                sourceRealizationRevisionId,
                realizationProfileFingerprint,
                predictiveQuotientFingerprint,
                identifiabilityFingerprint,
                informationPlanFingerprint,
                observationFingerprint,
                successorRealizationRevisionId,
                predecessorCycleFingerprint,
            )
        )
    }

    val executionAuthority: Boolean
        get() = false

    val directWorldMutationAllowed: Boolean
        get() = false

    fun withPredictiveState(
        predictiveQuotientFingerprint: String,
    ): MetaRealizationCycle = rebuild(
        state = MetaRealizationCycleState.PREDICTIVE_STATE_READY,
        predictiveQuotientFingerprint = predictiveQuotientFingerprint,
    )

    fun requireInformation(
        identifiabilityFingerprint: String,
    ): MetaRealizationCycle = rebuild(
        state = MetaRealizationCycleState.INFORMATION_REQUIRED,
        identifiabilityFingerprint = identifiabilityFingerprint,
    )

    fun awaitObservation(
        informationPlanFingerprint: String,
    ): MetaRealizationCycle = rebuild(
        state = MetaRealizationCycleState.AWAITING_OBSERVATION,
        informationPlanFingerprint = informationPlanFingerprint,
    )

    fun observe(
        observationFingerprint: String,
    ): MetaRealizationCycle = rebuild(
        state = MetaRealizationCycleState.OUTCOME_OBSERVED,
        observationFingerprint = observationFingerprint,
    )

    fun rebase(
        successorRealizationRevisionId: String,
    ): MetaRealizationCycle = rebuild(
        state = MetaRealizationCycleState.REBASED,
        successorRealizationRevisionId = successorRealizationRevisionId,
    )

    fun unresolved(): MetaRealizationCycle = rebuild(
        state = MetaRealizationCycleState.UNRESOLVED,
    )

    private fun rebuild(
        state: MetaRealizationCycleState,
        predictiveQuotientFingerprint: String? = this.predictiveQuotientFingerprint,
        identifiabilityFingerprint: String? = this.identifiabilityFingerprint,
        informationPlanFingerprint: String? = this.informationPlanFingerprint,
        observationFingerprint: String? = this.observationFingerprint,
        successorRealizationRevisionId: String? = this.successorRealizationRevisionId,
    ): MetaRealizationCycle = create(
        cycleId = cycleId,
        state = state,
        sourceRealizationRevisionId = sourceRealizationRevisionId,
        realizationProfileFingerprint = realizationProfileFingerprint,
        predictiveQuotientFingerprint = predictiveQuotientFingerprint,
        identifiabilityFingerprint = identifiabilityFingerprint,
        informationPlanFingerprint = informationPlanFingerprint,
        observationFingerprint = observationFingerprint,
        successorRealizationRevisionId = successorRealizationRevisionId,
        predecessorCycleFingerprint = fingerprint,
    )

    companion object {
        fun start(
            source: CanonicalRealizationState,
            profile: RealizationTransferProfile,
        ): MetaRealizationCycle {
            require(
                source.components.map { it.kind }.containsAll(profile.requiredComponents)
            ) {
                "Canonical realization state does not satisfy frozen profile components"
            }
            val cycleId = "meta-realization-cycle:" + StableFieldIds.fingerprint(
                "meta-realization-cycle-id/v1",
                source.revisionId,
                profile.fingerprint,
            )
            return create(
                cycleId = cycleId,
                state = MetaRealizationCycleState.STATE_FROZEN,
                sourceRealizationRevisionId = source.revisionId,
                realizationProfileFingerprint = profile.fingerprint,
            )
        }

        private fun create(
            cycleId: String,
            state: MetaRealizationCycleState,
            sourceRealizationRevisionId: String,
            realizationProfileFingerprint: String,
            predictiveQuotientFingerprint: String? = null,
            identifiabilityFingerprint: String? = null,
            informationPlanFingerprint: String? = null,
            observationFingerprint: String? = null,
            successorRealizationRevisionId: String? = null,
            predecessorCycleFingerprint: String? = null,
        ): MetaRealizationCycle {
            val fingerprint = expectedFingerprint(
                cycleId,
                state,
                sourceRealizationRevisionId,
                realizationProfileFingerprint,
                predictiveQuotientFingerprint,
                identifiabilityFingerprint,
                informationPlanFingerprint,
                observationFingerprint,
                successorRealizationRevisionId,
                predecessorCycleFingerprint,
            )
            return MetaRealizationCycle(
                cycleId = cycleId,
                state = state,
                sourceRealizationRevisionId = sourceRealizationRevisionId,
                realizationProfileFingerprint = realizationProfileFingerprint,
                predictiveQuotientFingerprint = predictiveQuotientFingerprint,
                identifiabilityFingerprint = identifiabilityFingerprint,
                informationPlanFingerprint = informationPlanFingerprint,
                observationFingerprint = observationFingerprint,
                successorRealizationRevisionId = successorRealizationRevisionId,
                predecessorCycleFingerprint = predecessorCycleFingerprint,
                fingerprint = fingerprint,
            )
        }

        private fun expectedFingerprint(
            cycleId: String,
            state: MetaRealizationCycleState,
            sourceRealizationRevisionId: String,
            realizationProfileFingerprint: String,
            predictiveQuotientFingerprint: String?,
            identifiabilityFingerprint: String?,
            informationPlanFingerprint: String?,
            observationFingerprint: String?,
            successorRealizationRevisionId: String?,
            predecessorCycleFingerprint: String?,
        ): String = StableFieldIds.fingerprint(
            "meta-realization-cycle/v1",
            cycleId,
            state.name,
            sourceRealizationRevisionId,
            realizationProfileFingerprint,
            predictiveQuotientFingerprint.orEmpty(),
            identifiabilityFingerprint.orEmpty(),
            informationPlanFingerprint.orEmpty(),
            observationFingerprint.orEmpty(),
            successorRealizationRevisionId.orEmpty(),
            predecessorCycleFingerprint.orEmpty(),
        )
    }
}
