package app.lifeos.core.field

data class FieldIteration(
    val index: Int,
    val maxDelta: Double,
    val stableRounds: Int,
    val fingerprint: String,
) {
    init {
        require(index >= 0) { "Iteration index must not be negative" }
        require(maxDelta.isFinite() && maxDelta >= 0.0) { "Iteration delta must be finite and non-negative" }
        require(stableRounds >= 0) { "Stable rounds must not be negative" }
        require(fingerprint.isNotBlank()) { "Iteration fingerprint must not be blank" }
    }
}

data class FieldEnergySnapshot(
    val nodeEnergy: Map<FieldNodeId, Double>,
    val hypothesisEnergy: Map<HypothesisId, Double>,
) {
    init {
        require(nodeEnergy.values.all { it.isFinite() && it in 0.0..1.0 }) {
            "Node energies must be finite and in 0..1"
        }
        require(hypothesisEnergy.values.all { it.isFinite() && it in 0.0..1.0 }) {
            "Hypothesis energies must be finite and in 0..1"
        }
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        *nodeEnergy.entries
            .sortedBy { it.key.value }
            .flatMap { listOf(it.key.value, stableDouble(it.value)) }
            .toTypedArray(),
        *hypothesisEnergy.entries
            .sortedBy { it.key.value }
            .flatMap { listOf(it.key.value, stableDouble(it.value)) }
            .toTypedArray(),
    )

    fun maxDeltaFrom(previous: FieldEnergySnapshot): Double {
        val nodeIds = nodeEnergy.keys + previous.nodeEnergy.keys
        val hypothesisIds = hypothesisEnergy.keys + previous.hypothesisEnergy.keys
        val nodeDelta = nodeIds.maxOfOrNull { id ->
            kotlin.math.abs((nodeEnergy[id] ?: 0.0) - (previous.nodeEnergy[id] ?: 0.0))
        } ?: 0.0
        val hypothesisDelta = hypothesisIds.maxOfOrNull { id ->
            kotlin.math.abs((hypothesisEnergy[id] ?: 0.0) - (previous.hypothesisEnergy[id] ?: 0.0))
        } ?: 0.0
        return maxOf(nodeDelta, hypothesisDelta)
    }
}

data class FieldState(
    val runId: FieldRunId,
    val domainId: FieldDomainId,
    val energy: FieldEnergySnapshot,
    val iteration: FieldIteration,
) {
    init {
        require(iteration.fingerprint == energy.fingerprint()) {
            "Field iteration fingerprint must match energy snapshot"
        }
    }

    fun next(nextEnergy: FieldEnergySnapshot, epsilon: Double): FieldState {
        require(epsilon.isFinite() && epsilon >= 0.0)
        val delta = nextEnergy.maxDeltaFrom(energy)
        val nextStableRounds = if (delta <= epsilon) iteration.stableRounds + 1 else 0
        return copy(
            energy = nextEnergy,
            iteration = FieldIteration(
                index = iteration.index + 1,
                maxDelta = delta,
                stableRounds = nextStableRounds,
                fingerprint = nextEnergy.fingerprint(),
            ),
        )
    }

    companion object {
        fun initial(
            domainId: FieldDomainId,
            inputFingerprint: String,
            energy: FieldEnergySnapshot,
        ): FieldState = FieldState(
            runId = StableFieldIds.run(domainId, inputFingerprint),
            domainId = domainId,
            energy = energy,
            iteration = FieldIteration(
                index = 0,
                maxDelta = 0.0,
                stableRounds = 0,
                fingerprint = energy.fingerprint(),
            ),
        )
    }
}

private fun stableDouble(value: Double): String = java.lang.Double.toHexString(value)
