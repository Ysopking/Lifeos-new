package app.lifeos.core.runtime.capability

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CapabilityRegistry {
    private val mutex = Mutex()
    private val providers = linkedMapOf<Pair<CapabilityId, String>, CapabilityDescriptor>()

    suspend fun register(descriptor: CapabilityDescriptor): CapabilityDescriptor = mutex.withLock {
        providers[descriptor.capabilityId to descriptor.providerId] = descriptor
        descriptor
    }

    suspend fun unregister(capabilityId: CapabilityId, providerId: String): CapabilityDescriptor? = mutex.withLock {
        providers.remove(capabilityId to providerId)
    }

    suspend fun providersFor(
        capabilityId: CapabilityId,
        includeUnavailable: Boolean = false,
    ): List<CapabilityDescriptor> = mutex.withLock {
        providers.values
            .asSequence()
            .filter { it.capabilityId == capabilityId }
            .filter {
                includeUnavailable || it.state == ProviderState.ACTIVE || it.state == ProviderState.DEGRADED
            }
            .sortedWith(
                compareByDescending<CapabilityDescriptor> { providerRank(it.state) }
                    .thenByDescending { it.reliability }
                    .thenBy { it.cost }
                    .thenBy { it.providerId }
            )
            .toList()
    }

    suspend fun all(includeUnavailable: Boolean = false): List<CapabilityDescriptor> = mutex.withLock {
        providers.values
            .filter {
                includeUnavailable || it.state == ProviderState.ACTIVE || it.state == ProviderState.DEGRADED
            }
            .sortedWith(compareBy<CapabilityDescriptor>({ it.capabilityId.value }, { it.providerId }))
    }

    private fun providerRank(state: ProviderState): Int = when (state) {
        ProviderState.ACTIVE -> 2
        ProviderState.DEGRADED -> 1
        ProviderState.QUARANTINED,
        ProviderState.DISABLED -> 0
    }
}

class CapabilityGapDetector(
    private val registry: CapabilityRegistry,
) {
    suspend fun detect(requirement: CapabilityRequirement): CapabilityGap? {
        val allProviders = registry.providersFor(requirement.capabilityId, includeUnavailable = true)
        val usable = allProviders.filter { provider ->
            provider.state == ProviderState.ACTIVE || provider.state == ProviderState.DEGRADED
        }
        if (usable.isEmpty()) {
            return CapabilityGap(
                requirement = requirement,
                type = if (allProviders.isEmpty()) {
                    CapabilityGapType.CAPABILITY_MISSING
                } else {
                    CapabilityGapType.PROVIDER_UNHEALTHY
                },
                candidateProviderIds = allProviders.map { it.providerId },
            )
        }

        val contractCompatible = usable.any { provider ->
            requirement.requiredInputs.containsAll(provider.contract.requiredInputs) &&
                provider.contract.outputs.containsAll(requirement.requiredOutputs)
        }
        if (!contractCompatible) {
            return CapabilityGap(
                requirement = requirement,
                type = CapabilityGapType.CONTRACT_MISMATCH,
                candidateProviderIds = usable.map { it.providerId },
            )
        }

        return null
    }
}
