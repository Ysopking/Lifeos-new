package app.lifeos.core.runtime.capability

/** Read-only projection of one generated tool for private diagnostics and user controls. */
data class GeneratedToolRuntimeItem(
    val toolId: String,
    val capabilityId: String,
    val state: GeneratedToolState,
    val verificationConfidence: Double,
    val trials: Int,
    val successes: Int,
    val expectedOutputs: Int,
    val safetyViolations: Int,
    val averageLatencyMs: Double,
    val activeProviderRegistered: Boolean,
    val lastMessage: String?,
) {
    init {
        require(toolId.isNotBlank())
        require(capabilityId.isNotBlank())
        require(verificationConfidence in 0.0..1.0)
        require(trials >= 0)
        require(successes in 0..trials)
        require(expectedOutputs in 0..trials)
        require(safetyViolations in 0..trials)
        require(averageLatencyMs >= 0.0)
    }
}

data class GeneratedToolRuntimeStatus(
    val tools: List<GeneratedToolRuntimeItem>,
    val generatedProviderIds: Set<String>,
) {
    init {
        require(tools.map { it.toolId } == tools.map { it.toolId }.sorted()) {
            "Generated-tool runtime status must be sorted by tool id"
        }
        require(tools.map { it.toolId }.distinct().size == tools.size) {
            "Generated-tool runtime status contains duplicate tool ids"
        }
        require(generatedProviderIds.none { it.isBlank() })
    }

    val totalTools: Int get() = tools.size
    val activeTools: Int get() = tools.count { it.state == GeneratedToolState.ACTIVE }
    val trialTools: Int get() = tools.count { it.state == GeneratedToolState.TRIAL }
    val quarantinedTools: Int get() = tools.count { it.state == GeneratedToolState.QUARANTINED }
    val totalTrials: Int get() = tools.sumOf { it.trials }
    val totalSafetyViolations: Int get() = tools.sumOf { it.safetyViolations }
}

/**
 * J12 read-only boundary. UI/kernel callers receive immutable status DTOs only; mutable registries,
 * trial ledgers and promotion primitives remain inside the process composition root.
 */
class GeneratedToolRuntimeStatusReader(
    private val tools: GeneratedToolRegistry,
    private val trialLedger: GeneratedToolTrialLedger,
    private val capabilityRegistry: CapabilityRegistry,
) {
    suspend fun snapshot(): GeneratedToolRuntimeStatus {
        val records = tools.snapshot()
        val generatedProviders = capabilityRegistry
            .all(includeUnavailable = true)
            .filter { it.providerType == ProviderType.GENERATED_TOOL }
        val providersById = generatedProviders.associateBy { it.providerId }

        val items = records.sortedBy { it.manifest.toolId }.map { record ->
            val stats = trialLedger.stats(record.manifest.toolId)
            val provider = providersById[record.manifest.toolId]
            GeneratedToolRuntimeItem(
                toolId = record.manifest.toolId,
                capabilityId = record.manifest.sourceCapability.value,
                state = record.state,
                verificationConfidence = record.verificationConfidence,
                trials = stats.trials,
                successes = stats.successes,
                expectedOutputs = stats.expectedOutputs,
                safetyViolations = stats.safetyViolations,
                averageLatencyMs = stats.averageLatencyMs,
                activeProviderRegistered = record.state == GeneratedToolState.ACTIVE &&
                    provider != null &&
                    provider.capabilityId == record.manifest.sourceCapability &&
                    (provider.state == ProviderState.ACTIVE || provider.state == ProviderState.DEGRADED),
                lastMessage = record.lastMessage,
            )
        }

        return GeneratedToolRuntimeStatus(
            tools = items,
            generatedProviderIds = generatedProviders.map { it.providerId }.toSortedSet(),
        )
    }
}
