package app.lifeos.core.runtime.capability

import app.lifeos.core.field.StableFieldIds
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** In-process exclusion claim that closes the missing-capability race during bounded promotion. */
internal data class GeneratedToolNovelActivationClaim(
    val capabilityId: CapabilityId,
    val toolId: String,
    val activationEvidenceId: String,
) {
    init {
        require(toolId.isNotBlank())
        require(activationEvidenceId.isNotBlank())
    }

    val id: String = StableFieldIds.fingerprint(
        "generated-tool-novel-activation-claim/v1",
        capabilityId.value,
        toolId,
        activationEvidenceId,
    )
}

class CapabilityRegistry(
    initialProviders: Iterable<CapabilityDescriptor> = emptyList(),
) {
    private val mutex = Mutex()
    private val providers = linkedMapOf<Pair<CapabilityId, String>, CapabilityDescriptor>()
    private val novelActivationClaims = mutableMapOf<CapabilityId, GeneratedToolNovelActivationClaim>()

    init {
        initialProviders.forEach { descriptor ->
            require(descriptor.providerType != ProviderType.GENERATED_TOOL) {
                "Generated capability providers require guarded promotion evidence"
            }
            providers[descriptor.capabilityId to descriptor.providerId] = descriptor
        }
    }

    suspend fun register(descriptor: CapabilityDescriptor): CapabilityDescriptor = mutex.withLock {
        require(descriptor.providerType != ProviderType.GENERATED_TOOL) {
            "Generated capability providers require guarded promotion evidence"
        }
        require(descriptor.capabilityId !in novelActivationClaims) {
            "Capability is reserved by an in-flight bounded generated-tool activation"
        }
        providers[descriptor.capabilityId to descriptor.providerId] = descriptor
        descriptor
    }

    internal suspend fun registerGenerated(
        descriptor: CapabilityDescriptor,
        activeRecord: GeneratedToolRecord,
        evidence: GeneratedToolActivationEvidence,
    ): CapabilityDescriptor = mutex.withLock {
        require(descriptor.capabilityId !in novelActivationClaims) {
            "Replacement generated provider cannot bypass an in-flight novel activation claim"
        }
        requireGeneratedDescriptor(descriptor, activeRecord)
        requireEvidenceBinding(activeRecord, evidence)
        providers[descriptor.capabilityId to descriptor.providerId] = descriptor
        descriptor
    }

    internal suspend fun claimMissingForGenerated(
        capabilityId: CapabilityId,
        toolId: String,
        activationEvidenceId: String,
    ): GeneratedToolNovelActivationClaim = mutex.withLock {
        val existingProviders = providers.values.filter { it.capabilityId == capabilityId }
        require(existingProviders.isEmpty()) {
            "Novel generated-tool activation requires the capability to remain completely missing"
        }
        val requested = GeneratedToolNovelActivationClaim(capabilityId, toolId, activationEvidenceId)
        novelActivationClaims[capabilityId]?.let { existing ->
            require(existing == requested) { "Capability already has another activation claim" }
            return@withLock existing
        }
        novelActivationClaims[capabilityId] = requested
        requested
    }

    internal suspend fun preflightGeneratedNovel(
        descriptor: CapabilityDescriptor,
        prospectiveActiveRecord: GeneratedToolRecord,
        evidence: GeneratedToolActivationEvidence,
        claim: GeneratedToolNovelActivationClaim,
    ) = mutex.withLock {
        require(novelActivationClaims[descriptor.capabilityId] == claim) {
            "Novel activation claim is missing or stale"
        }
        require(providers.values.none { it.capabilityId == descriptor.capabilityId }) {
            "Provider appeared while novel activation was claimed"
        }
        require(claim.toolId == prospectiveActiveRecord.manifest.toolId)
        require(claim.activationEvidenceId == evidence.id)
        requireGeneratedDescriptor(descriptor, prospectiveActiveRecord)
        requireEvidenceBinding(prospectiveActiveRecord, evidence)
    }

    internal suspend fun registerGeneratedNovel(
        descriptor: CapabilityDescriptor,
        activeRecord: GeneratedToolRecord,
        evidence: GeneratedToolActivationEvidence,
        claim: GeneratedToolNovelActivationClaim,
    ): CapabilityDescriptor = mutex.withLock {
        require(novelActivationClaims[descriptor.capabilityId] == claim) {
            "Novel activation claim is missing or stale"
        }
        require(providers.values.none { it.capabilityId == descriptor.capabilityId }) {
            "Provider appeared while novel activation was claimed"
        }
        require(claim.toolId == activeRecord.manifest.toolId)
        require(claim.activationEvidenceId == evidence.id)
        requireGeneratedDescriptor(descriptor, activeRecord)
        requireEvidenceBinding(activeRecord, evidence)
        providers[descriptor.capabilityId to descriptor.providerId] = descriptor
        novelActivationClaims.remove(descriptor.capabilityId)
        descriptor
    }

    internal suspend fun releaseNovelActivationClaim(claim: GeneratedToolNovelActivationClaim) = mutex.withLock {
        if (novelActivationClaims[claim.capabilityId] == claim) {
            novelActivationClaims.remove(claim.capabilityId)
        }
    }

    /** Boot-only J03 restore path. Bounded ACTIVE restore remains gated until V1.5. */
    internal suspend fun registerGeneratedRestored(
        descriptor: CapabilityDescriptor,
        activeRecord: GeneratedToolRecord,
        receipt: GeneratedToolPromotionReceipt,
    ): CapabilityDescriptor = mutex.withLock {
        require(descriptor.capabilityId !in novelActivationClaims)
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

    private fun requireEvidenceBinding(
        activeRecord: GeneratedToolRecord,
        evidence: GeneratedToolActivationEvidence,
    ) {
        require(!evidence.activationAllowed) {
            "Generated capability registration requires non-authoritative activation evidence"
        }
        require(activeRecord.promotionEvidenceId == evidence.id) {
            "Generated capability registration requires the accepted promotion evidence"
        }
        require(evidence.toolId == activeRecord.manifest.toolId)
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
                type = if (allProviders.isEmpty()) CapabilityGapType.CAPABILITY_MISSING else CapabilityGapType.PROVIDER_UNHEALTHY,
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
