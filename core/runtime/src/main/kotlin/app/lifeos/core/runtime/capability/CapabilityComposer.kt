package app.lifeos.core.runtime.capability

class CapabilityComposer(
    private val registry: CapabilityRegistry,
    private val maxSteps: Int = 16,
) {
    init {
        require(maxSteps > 0) { "Composition max steps must be positive" }
    }

    suspend fun compose(
        availableContracts: Set<String>,
        requiredOutputs: Set<String>,
    ): CompositeCapabilityPlan? {
        if (availableContracts.containsAll(requiredOutputs)) {
            return CompositeCapabilityPlan(emptyList(), availableContracts)
        }

        val remaining = registry.all()
            .filter { it.state == ProviderState.ACTIVE || it.state == ProviderState.DEGRADED }
            .toMutableList()
        val contracts = availableContracts.toMutableSet()
        val steps = mutableListOf<CapabilityDescriptor>()

        while (!contracts.containsAll(requiredOutputs) && steps.size < maxSteps) {
            val candidates = remaining
                .asSequence()
                .filter { contracts.containsAll(it.contract.requiredInputs) }
                .filter { provider -> provider.contract.outputs.any { it !in contracts } }
                .sortedWith(
                    compareByDescending<CapabilityDescriptor> { provider ->
                        provider.contract.outputs.count { it in requiredOutputs && it !in contracts }
                    }
                        .thenByDescending { it.reliability }
                        .thenBy { it.cost }
                        .thenBy { it.providerId }
                )
                .toList()

            val next = candidates.firstOrNull() ?: break
            steps += next
            contracts += next.contract.outputs
            remaining.remove(next)
        }

        return if (contracts.containsAll(requiredOutputs)) {
            CompositeCapabilityPlan(steps, contracts)
        } else {
            null
        }
    }
}
