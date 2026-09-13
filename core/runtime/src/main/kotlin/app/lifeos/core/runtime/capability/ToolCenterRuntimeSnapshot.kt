package app.lifeos.core.runtime.capability

/** Availability of the productive capability/generated-tool process pair. */
enum class ToolCenterRuntimeAvailability {
    UNAVAILABLE,
    PARTIAL,
    READY,
}

/** Immutable read-only view of one productive capability provider. */
data class ToolCenterCapabilityProviderSnapshot(
    val capabilityId: String,
    val providerId: String,
    val providerType: ProviderType,
    val state: ProviderState,
    val trustLevel: TrustLevel,
    val reliability: Double,
    val cost: Double,
    val requiredInputs: List<String>,
    val outputs: List<String>,
) {
    init {
        require(capabilityId.isNotBlank())
        require(providerId.isNotBlank())
        require(reliability in 0.0..1.0)
        require(cost.isFinite() && cost >= 0.0)
        require(requiredInputs == requiredInputs.sorted())
        require(outputs == outputs.sorted())
    }
}

/** Immutable read-only view of one generated tool currently known by the productive registry. */
data class ToolCenterGeneratedToolSnapshot(
    val toolId: String,
    val capabilityId: String,
    val state: GeneratedToolState,
    val verificationConfidence: Double,
    val permissions: List<String>,
    val requiredInputs: List<String>,
    val requiredOutputs: List<String>,
    val promotionEvidenceId: String?,
    val lastMessage: String?,
) {
    init {
        require(toolId.isNotBlank())
        require(capabilityId.isNotBlank())
        require(verificationConfidence in 0.0..1.0)
        require(permissions == permissions.sorted())
        require(requiredInputs == requiredInputs.sorted())
        require(requiredOutputs == requiredOutputs.sorted())
        require(promotionEvidenceId == null || promotionEvidenceId.isNotBlank())
        require(lastMessage == null || lastMessage.isNotBlank())
    }
}

/**
 * Stable F8 boundary for the Tool Center. It exposes values only; mutable registries never escape.
 */
data class ToolCenterRuntimeSnapshot(
    val availability: ToolCenterRuntimeAvailability,
    val capabilityProviders: List<ToolCenterCapabilityProviderSnapshot>,
    val generatedTools: List<ToolCenterGeneratedToolSnapshot>,
) {
    init {
        require(
            capabilityProviders == capabilityProviders.sortedWith(
                compareBy<ToolCenterCapabilityProviderSnapshot>({ it.capabilityId }, { it.providerId })
            )
        ) { "Tool Center capability providers must use canonical order" }
        require(
            capabilityProviders.map { it.capabilityId to it.providerId }.distinct().size == capabilityProviders.size
        ) { "Tool Center capability providers contain duplicate identities" }
        require(generatedTools == generatedTools.sortedBy { it.toolId }) {
            "Tool Center generated tools must use canonical order"
        }
        require(generatedTools.map { it.toolId }.distinct().size == generatedTools.size) {
            "Tool Center generated tools contain duplicate identities"
        }
    }

    val capabilityCount: Int get() = capabilityProviders.map { it.capabilityId }.distinct().size
    val providerCount: Int get() = capabilityProviders.size
    val generatedToolCount: Int get() = generatedTools.size
}

/**
 * Read-only reader over the process-owned runtime registries. The reader intentionally requests
 * unavailable providers too so quarantine/disable state remains visible to the private Tool Center.
 */
class ToolCenterRuntimeSnapshotReader {
    suspend fun snapshot(): ToolCenterRuntimeSnapshot = snapshot(
        capabilities = GeneratedToolRuntimeProcessRegistry.capabilities(),
        tools = GeneratedToolRuntimeProcessRegistry.tools(),
    )

    internal suspend fun snapshot(
        capabilities: CapabilityRegistry?,
        tools: GeneratedToolRegistry?,
    ): ToolCenterRuntimeSnapshot {
        val providerSnapshots = capabilities
            ?.all(includeUnavailable = true)
            .orEmpty()
            .map { provider ->
                ToolCenterCapabilityProviderSnapshot(
                    capabilityId = provider.capabilityId.value,
                    providerId = provider.providerId,
                    providerType = provider.providerType,
                    state = provider.state,
                    trustLevel = provider.trustLevel,
                    reliability = provider.reliability,
                    cost = provider.cost,
                    requiredInputs = provider.contract.requiredInputs.sorted(),
                    outputs = provider.contract.outputs.sorted(),
                )
            }
            .sortedWith(compareBy({ it.capabilityId }, { it.providerId }))

        val toolSnapshots = tools
            ?.snapshot()
            .orEmpty()
            .map { record ->
                ToolCenterGeneratedToolSnapshot(
                    toolId = record.manifest.toolId,
                    capabilityId = record.manifest.sourceCapability.value,
                    state = record.state,
                    verificationConfidence = record.verificationConfidence,
                    permissions = record.manifest.permissions.map { it.name }.sorted(),
                    requiredInputs = record.manifest.requiredInputs.sorted(),
                    requiredOutputs = record.manifest.requiredOutputs.sorted(),
                    promotionEvidenceId = record.promotionEvidenceId,
                    lastMessage = record.lastMessage,
                )
            }
            .sortedBy { it.toolId }

        val availability = when {
            capabilities != null && tools != null -> ToolCenterRuntimeAvailability.READY
            capabilities != null || tools != null -> ToolCenterRuntimeAvailability.PARTIAL
            else -> ToolCenterRuntimeAvailability.UNAVAILABLE
        }

        return ToolCenterRuntimeSnapshot(
            availability = availability,
            capabilityProviders = providerSnapshots,
            generatedTools = toolSnapshots,
        )
    }
}
