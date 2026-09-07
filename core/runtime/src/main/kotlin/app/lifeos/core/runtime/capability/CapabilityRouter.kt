package app.lifeos.core.runtime.capability

data class CapabilityRoutingContext(
    val preferLowLatency: Boolean = false,
    val critical: Boolean = false,
    val maxCost: Double? = null,
) {
    init {
        require(maxCost == null || (maxCost.isFinite() && maxCost >= 0.0)) {
            "Maximum routing cost must be finite and non-negative"
        }
    }
}

fun interface CapabilityRoutingPolicy {
    fun score(provider: CapabilityDescriptor, context: CapabilityRoutingContext): Double
}

class DefaultCapabilityRoutingPolicy : CapabilityRoutingPolicy {
    override fun score(provider: CapabilityDescriptor, context: CapabilityRoutingContext): Double {
        val stateFactor = when (provider.state) {
            ProviderState.ACTIVE -> 1.0
            ProviderState.DEGRADED -> 0.6
            ProviderState.QUARANTINED,
            ProviderState.DISABLED -> 0.0
        }
        val trustFactor = when (provider.trustLevel) {
            TrustLevel.LOW -> 0.5
            TrustLevel.MEDIUM -> 0.75
            TrustLevel.HIGH -> 0.9
            TrustLevel.SYSTEM -> 1.0
        }
        val criticalTrust = if (context.critical) trustFactor else 1.0
        val costPenalty = 1.0 / (1.0 + provider.cost)
        return stateFactor * provider.reliability * trustFactor * criticalTrust * costPenalty
    }
}

class CapabilityRouter(
    private val registry: CapabilityRegistry,
    private val policy: CapabilityRoutingPolicy = DefaultCapabilityRoutingPolicy(),
) {
    suspend fun route(
        requirement: CapabilityRequirement,
        context: CapabilityRoutingContext = CapabilityRoutingContext(),
    ): CapabilityDescriptor? {
        return registry.providersFor(requirement.capabilityId)
            .asSequence()
            .filter { provider ->
                requirement.requiredInputs.containsAll(provider.contract.requiredInputs) &&
                    provider.contract.outputs.containsAll(requirement.requiredOutputs)
            }
            .filter { provider -> context.maxCost == null || provider.cost <= context.maxCost }
            .map { provider -> provider to policy.score(provider, context) }
            .filter { (_, score) -> score.isFinite() && score > 0.0 }
            .sortedWith(
                compareByDescending<Pair<CapabilityDescriptor, Double>> { it.second }
                    .thenByDescending { it.first.reliability }
                    .thenBy { it.first.cost }
                    .thenBy { it.first.providerId }
            )
            .firstOrNull()
            ?.first
    }
}
