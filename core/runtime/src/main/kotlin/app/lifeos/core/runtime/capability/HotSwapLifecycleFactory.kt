package app.lifeos.core.runtime.capability

/**
 * Reconstructs the promotion-validation side of the generated-tool lifecycle from durable state.
 * The returned coordinator shares the productive tool/capability registries but owns a read-only
 * replay of trial evidence, so hot-swap can reject stale promotion evidence without introducing a
 * second mutable trial pipeline.
 */
class HotSwapLifecycleFactory(
    private val repository: GeneratedToolStateRepository,
    private val tools: GeneratedToolRegistry,
    private val capabilities: CapabilityRegistry,
    private val promotionPolicy: GeneratedToolPromotionPolicy = GeneratedToolPromotionPolicy(),
) {
    suspend fun create(): GeneratedToolLifecycleCoordinator {
        val durable = repository.loadAll().sortedBy { it.record.manifest.toolId }
        require(durable.map { it.record.manifest.toolId }.distinct().size == durable.size) {
            "Durable generated-tool state contains duplicate tool identities"
        }

        val trials = GeneratedToolTrialLedger()
        durable.forEach { state ->
            GeneratedToolStateIntegrity.requireValidAudit(state.record, state.auditEntries)
            trials.restore(state.trialEvidence)
        }

        return GeneratedToolLifecycleCoordinator(
            tools = tools,
            trialLedger = trials,
            capabilityRegistry = capabilities,
            promotionPolicy = promotionPolicy,
        )
    }
}
