package app.lifeos.core.runtime.capability

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-boot adapter around the strict J10 rehydrator. The first invocation restores an empty
 * runtime. A later bootstrap retry never restores twice; it verifies that the already loaded RAM
 * registry, audit chains and trial ledger still exactly match the durable vault.
 */
class GeneratedToolBootStateRehydrator(
    private val repository: GeneratedToolStateRepository,
    private val tools: GeneratedToolRegistry,
    private val trialLedger: GeneratedToolTrialLedger,
    private val capabilityRegistry: CapabilityRegistry? = null,
    private val promotionPolicy: GeneratedToolPromotionPolicy = GeneratedToolPromotionPolicy(),
) {
    private val mutex = Mutex()

    suspend fun rehydrateOrVerify(): GeneratedToolRehydrationReport = mutex.withLock {
        val durable = repository.loadAll().sortedBy { it.record.manifest.toolId }
        val loaded = tools.snapshot().sortedBy { it.manifest.toolId }
        val generatedProviders = capabilityRegistry
            ?.all(includeUnavailable = true)
            ?.filter { it.providerType == ProviderType.GENERATED_TOOL }
            .orEmpty()

        if (loaded.isEmpty() && trialLedger.isEmpty() && generatedProviders.isEmpty()) {
            return@withLock GeneratedToolStateRehydrator(
                repository = repository,
                tools = tools,
                trialLedger = trialLedger,
                capabilityRegistry = capabilityRegistry,
                promotionPolicy = promotionPolicy,
            ).rehydrate()
        }

        require(loaded == durable.map { it.record }) {
            "Generated-tool boot retry found RAM records different from durable state"
        }
        for (state in durable) {
            require(tools.auditSnapshot(state.record.manifest.toolId) == state.auditEntries) {
                "Generated-tool boot retry found an audit chain different from durable state"
            }
            require(trialLedger.evidence(state.record.manifest.toolId) == state.trialEvidence) {
                "Generated-tool boot retry found trial evidence different from durable state"
            }
        }

        val activeIds = durable
            .filter { it.record.state == GeneratedToolState.ACTIVE }
            .map { it.record.manifest.toolId }
            .toSet()
        if (capabilityRegistry != null) {
            val providerIds = generatedProviders.map { it.providerId }.toSet()
            require(providerIds == activeIds) {
                "Generated-tool boot retry found generated providers different from ACTIVE durable tools"
            }
        }

        GeneratedToolRehydrationReport(
            restoredTools = durable.size,
            restoredTrialResults = durable.sumOf { it.trialEvidence.stats.trials },
            restoredActiveProviders = if (capabilityRegistry == null) 0 else activeIds.size,
        )
    }
}
