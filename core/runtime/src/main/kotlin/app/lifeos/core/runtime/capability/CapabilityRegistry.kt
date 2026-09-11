package app.lifeos.core.runtime.capability

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CapabilityRegistry(
    initialProviders: Iterable<CapabilityDescriptor> = emptyList(),
) {
    private val mutex = Mutex()
    private val providers = linkedMapOf<Pair<CapabilityId, String>, CapabilityDescriptor>()

    init {
        initialProviders.forEach { descriptor ->
            require(descriptor.providerType != ProviderType.GENERATED_TOOL) {
                "Generated capability providers require J03 promotion evidence"
            }
            providers[descriptor.capabilityId to descriptor.providerId] = descriptor
        }
    }

    suspend fun register(descriptor: CapabilityDescriptor): CapabilityDescriptor = mutex.withLock {
        require(descriptor.providerType != ProviderType.GENERATED_TOOL) {
            "Generated capability providers require J03 promotion evidence"
        }
        providers[descriptor.capabilityId to descriptor.providerId] = descriptor
        descriptor
    }

    internal suspend fun registerGenerated(
        descriptor: CapabilityDescriptor,
        activeRecord: GeneratedToolRecord,
        evidence: GeneratedToolActivationEvidence,
    ): CapabilityDescriptor = mutex.withLock {
        requireGeneratedDescriptor(descriptor, activeRecord)
        require(!evidence.activationAllowed) {
            "Generated capability registration requires non-authoritative activation evidence"
        }
        require(activeRecord.promotionEvidenceId == evidence.id) {
            "Generated capability registration requires the accepted promotion evidence"
        }
        require(evidence.toolId == activeRecord.manifest.toolId)
        providers[descriptor.capabilityId to descriptor.providerId] = descriptor
        descriptor
    }

    /**
     * J10 boot-only registration path. A persisted receipt cannot invoke promotion; it only proves
     * that the already ACTIVE record was previously promoted with the exact accepted J03 evidence.
     */
    internal suspend fun registerGeneratedRestored(
        descriptor: CapabilityDescriptor,
        activeRecord: GeneratedToolRecord,
        receipt: GeneratedToolPromotionReceipt,
    ): CapabilityDescriptor = mutex.withLock {
        requireGeneratedDescriptor(descriptor, activeRecord)
        require(!receipt.activationAllowed)
        require(activeRecord.promotionEvidenceId == receipt.evidenceId) {
            "Generated capability restore requires the accepted promotion receipt"
        }
        require(receipt.toolId == activeRecord.manifest.toolId)
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

    private fun requireGeneratedDescriptor(
        descriptor: CapabilityDescriptor,
        activeRecord: GeneratedToolRecord,
    ) {
        require(descriptor.providerType == ProviderType.GENERATED_TOOL)
        require(activeRecord.state == GeneratedToolState.ACTIVE) {
            "Generated capability registration requires ACTIVE generated-tool record"
        }
        require(descriptor.providerId == activeRecord.manifest.toolId)
        require(descriptor.capabilityId == activeRecord.manifest.sourceCapability)
        require(descriptor.contract.requiredInputs == activeRecord.manifest.requiredInputs)
        require(descriptor.contract.outputs == activeRecord.manifest.requiredOutputs)
        require(descriptor.state == ProviderState.ACTIVE)
        require(descriptor.trustLevel == TrustLevel.LOW) {
            "Generated capability providers start at LOW trust"
        }
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
