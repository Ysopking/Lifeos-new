package app.lifeos.core.runtime.capability

@JvmInline
value class CapabilityId(val value: String) {
    init {
        require(value.isNotBlank()) { "Capability id must not be blank" }
    }

    override fun toString(): String = value
}

enum class ProviderType {
    MODULE,
    WORKER,
    INTERNAL_TOOL,
    EXTERNAL_TOOL,
    CONNECTOR,
    COMPOSITE_CAPABILITY,
    GENERATED_TOOL,
}

enum class ProviderState {
    ACTIVE,
    DEGRADED,
    QUARANTINED,
    DISABLED,
}

enum class TrustLevel {
    LOW,
    MEDIUM,
    HIGH,
    SYSTEM,
}

data class CapabilityContract(
    val requiredInputs: Set<String> = emptySet(),
    val outputs: Set<String> = emptySet(),
) {
    init {
        require(requiredInputs.none { it.isBlank() }) { "Input contracts must not be blank" }
        require(outputs.none { it.isBlank() }) { "Output contracts must not be blank" }
    }
}

data class CapabilityDescriptor(
    val capabilityId: CapabilityId,
    val providerId: String,
    val providerType: ProviderType,
    val contract: CapabilityContract = CapabilityContract(),
    val state: ProviderState = ProviderState.ACTIVE,
    val trustLevel: TrustLevel = TrustLevel.MEDIUM,
    val reliability: Double = 1.0,
    val cost: Double = 0.0,
) {
    init {
        require(providerId.isNotBlank()) { "Provider id must not be blank" }
        require(reliability in 0.0..1.0) { "Reliability must be between zero and one" }
        require(cost.isFinite() && cost >= 0.0) { "Cost must be finite and non-negative" }
    }
}

enum class GapSeverity {
    NON_BLOCKING,
    DEGRADED,
    BLOCKING,
    CRITICAL,
}

enum class CapabilityGapType {
    CAPABILITY_MISSING,
    PROVIDER_UNHEALTHY,
    CONTRACT_MISMATCH,
}

data class CapabilityRequirement(
    val capabilityId: CapabilityId,
    val severity: GapSeverity = GapSeverity.BLOCKING,
    val requiredInputs: Set<String> = emptySet(),
    val requiredOutputs: Set<String> = emptySet(),
)

data class CapabilityGap(
    val requirement: CapabilityRequirement,
    val type: CapabilityGapType,
    val candidateProviderIds: List<String> = emptyList(),
)

data class CompositeCapabilityPlan(
    val steps: List<CapabilityDescriptor>,
    val resultingContracts: Set<String>,
) {
    val expectedReliability: Double = steps.fold(1.0) { acc, step -> acc * step.reliability }
    val expectedCost: Double = steps.sumOf { it.cost }
}
