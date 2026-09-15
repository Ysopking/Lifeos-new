package app.lifeos.core.model

/** Outcome tied to canonical module-processing evidence. */
data class ModuleOutcome(
    val processing: ModuleProcessingRecord,
    val utilityMicros: Long,
    val accepted: Boolean,
    val evidencePhotonIds: List<PhotonId> = emptyList(),
) {
    init {
        require(utilityMicros in -1_000_000L..1_000_000L) { "Utility must be normalized to micros" }
        require(evidencePhotonIds.distinct().size == evidencePhotonIds.size) { "Outcome evidence ids must be unique" }
    }

    val stableFingerprint: String
        get() = StableCognitiveIds.fingerprint(
            processing.processingId.value,
            processing.module.stableFingerprint,
            utilityMicros.toString(),
            accepted.toString(),
            *evidencePhotonIds.map { it.value }.sorted().toTypedArray(),
        )
}

/** Observation metadata remains separate from the outcome value itself. */
data class ModuleOutcomeEvidence(
    val outcomeFingerprint: String,
    val evidencePhotonIds: List<PhotonId>,
    val observedAtRevision: Long,
) {
    init {
        require(outcomeFingerprint.isNotBlank()) { "Outcome fingerprint must not be blank" }
        require(observedAtRevision > 0) { "Observed revision must be positive" }
        require(evidencePhotonIds.distinct().size == evidencePhotonIds.size) { "Outcome evidence ids must be unique" }
    }
}

data class ModuleUtilitySnapshot(
    val module: ModuleIdentity,
    val observations: Long,
    val acceptedObservations: Long,
    val utilityMicrosTotal: Long,
) {
    init {
        require(observations >= 0) { "Observations must not be negative" }
        require(acceptedObservations in 0..observations) { "Accepted observations must be bounded" }
    }

    val meanUtilityMicros: Long
        get() = if (observations == 0L) 0L else utilityMicrosTotal / observations

    /** Observed acceptance only; this is never module authority or policy priority. */
    val acceptanceRateMicros: Long
        get() = if (observations == 0L) 0L
        else Math.multiplyExact(acceptedObservations, 1_000_000L) / observations

    fun observe(outcome: ModuleOutcome): ModuleUtilitySnapshot {
        require(outcome.processing.module.stableFingerprint == module.stableFingerprint) {
            "Outcome must belong to this module implementation"
        }
        return copy(
            observations = observations + 1,
            acceptedObservations = acceptedObservations + if (outcome.accepted) 1 else 0,
            utilityMicrosTotal = Math.addExact(utilityMicrosTotal, outcome.utilityMicros),
        )
    }

    companion object {
        fun empty(module: ModuleIdentity) = ModuleUtilitySnapshot(module, 0, 0, 0)
    }
}
