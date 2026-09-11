package app.lifeos.core.runtime.capability

import app.lifeos.core.runtime.evolution.NovelCapabilityPromotionStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-boot adapter around generated-tool rehydration. Legacy J03 states keep their established
 * path; snapshots containing bounded ACTIVE tools use the stricter V1.5 artifact/seal replay gate.
 * A later bootstrap retry never restores twice and verifies RAM against the durable source of truth.
 * V10 routing cutovers and V11 workshop jobs are reconciled only after promoted/generated tool
 * records have been restored into the productive process registries.
 *
 * V14 invariant: durable ACTIVE state is evidence only. Re-registering an ACTIVE generated provider
 * requires a fresh GeneratedProviderRestoreAuthority decision immediately around the registry
 * mutation. Missing/revoked/corrupt authority leaves durable tool/trial state readable but unroutable.
 */
class GeneratedToolBootStateRehydrator(
    private val repository: GeneratedToolStateRepository,
    private val tools: GeneratedToolRegistry,
    private val trialLedger: GeneratedToolTrialLedger,
    private val capabilityRegistry: CapabilityRegistry? = null,
    private val promotionPolicy: GeneratedToolPromotionPolicy = GeneratedToolPromotionPolicy(),
    private val artifactRepository: GeneratedToolArtifactRepository? = null,
    private val novelPromotionStore: NovelCapabilityPromotionStore? = null,
    private val providerRestoreAuthority: GeneratedProviderRestoreAuthority? =
        GeneratedProviderRestoreAuthorityRuntimeRegistry.current(),
) {
    private val mutex = Mutex()

    suspend fun rehydrateOrVerify(): GeneratedToolRehydrationReport = mutex.withLock {
        val durable = repository.loadAll().sortedBy { it.record.manifest.toolId }
        val loaded = tools.snapshot().sortedBy { it.manifest.toolId }
        val generatedProviders = capabilityRegistry
            ?.all(includeUnavailable = true)
            ?.filter { it.providerType == ProviderType.GENERATED_TOOL }
            .orEmpty()

        val report = if (loaded.isEmpty() && trialLedger.isEmpty() && generatedProviders.isEmpty()) {
            val hasBoundedActive = durable.any { state ->
                state.record.state == GeneratedToolState.ACTIVE && state.boundedPromotionReceipt != null
            }
            if (hasBoundedActive) {
                BoundedGeneratedToolStateRehydrator(
                    repository = repository,
                    tools = tools,
                    trialLedger = trialLedger,
                    capabilityRegistry = capabilityRegistry,
                    artifacts = requireNotNull(artifactRepository) {
                        "Bounded ACTIVE boot restore requires the generated-tool artifact repository"
                    },
                    novelPromotionStore = requireNotNull(novelPromotionStore) {
                        "Bounded ACTIVE boot restore requires the durable Novel Canary promotion store"
                    },
                    promotionPolicy = promotionPolicy,
                    providerRestoreAuthority = providerRestoreAuthority,
                ).rehydrate(durable)
            } else {
                GeneratedToolStateRehydrator(
                    repository = repository,
                    tools = tools,
                    trialLedger = trialLedger,
                    capabilityRegistry = capabilityRegistry,
                    promotionPolicy = promotionPolicy,
                    providerRestoreAuthority = providerRestoreAuthority,
                ).rehydrate()
            }
        } else {
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

            val activeStates = durable.filter { it.record.state == GeneratedToolState.ACTIVE }
            val expectedProviderIds = if (capabilityRegistry == null) {
                emptySet()
            } else {
                activeStates.filter { state ->
                    providerRestoreAuthority?.allowedNow(state.record) == true
                }.map { it.record.manifest.toolId }.toSet()
            }
            if (capabilityRegistry != null) {
                val providerIds = generatedProviders.map { it.providerId }.toSet()
                require(providerIds == expectedProviderIds) {
                    "Generated-tool boot retry found generated providers different from currently owner-authorized ACTIVE durable tools"
                }
            }

            GeneratedToolRehydrationReport(
                restoredTools = durable.size,
                restoredTrialResults = durable.sumOf { it.trialEvidence.stats.trials },
                restoredActiveProviders = expectedProviderIds.size,
            )
        }

        HotSwapBootRuntimeRegistry.reconcileIfInstalled()
        ToolWorkshopBootRuntimeRegistry.reconcileIfInstalled()
        report
    }
}
