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
 * V14 invariant: durable ACTIVE state is evidence only. Legacy/bounded rehydrators reconstruct
 * lifecycle and trial evidence with no CapabilityRegistry attached. A single common step then makes
 * each ACTIVE provider routable only inside a fresh GeneratedProviderRestoreAuthority exposure.
 * Missing/revoked/corrupt authority leaves durable state readable but the provider unroutable.
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
            val stateReport = if (hasBoundedActive) {
                BoundedGeneratedToolStateRehydrator(
                    repository = repository,
                    tools = tools,
                    trialLedger = trialLedger,
                    capabilityRegistry = null,
                    artifacts = requireNotNull(artifactRepository) {
                        "Bounded ACTIVE boot restore requires the generated-tool artifact repository"
                    },
                    novelPromotionStore = requireNotNull(novelPromotionStore) {
                        "Bounded ACTIVE boot restore requires the durable Novel Canary promotion store"
                    },
                    promotionPolicy = promotionPolicy,
                ).rehydrate(durable)
            } else {
                GeneratedToolStateRehydrator(
                    repository = repository,
                    tools = tools,
                    trialLedger = trialLedger,
                    capabilityRegistry = null,
                    promotionPolicy = promotionPolicy,
                ).rehydrate()
            }
            stateReport.copy(restoredActiveProviders = restoreAuthorizedProviders(durable))
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

            val expectedProviderIds = if (capabilityRegistry == null) {
                emptySet()
            } else {
                durable.filter { it.record.state == GeneratedToolState.ACTIVE }
                    .filter { state -> providerRestoreAuthority?.allowedNow(state.record) == true }
                    .map { it.record.manifest.toolId }
                    .toSet()
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

    private suspend fun restoreAuthorizedProviders(
        durable: List<GeneratedToolPersistentState>,
    ): Int {
        val registry = capabilityRegistry ?: return 0
        val authority = providerRestoreAuthority ?: return 0
        var restored = 0
        for (state in durable.filter { it.record.state == GeneratedToolState.ACTIVE }) {
            val descriptor = state.toCapabilityDescriptor()
            val exposed = authority.expose(state.record) {
                state.promotionReceipt?.let { legacy ->
                    registry.registerGeneratedRestored(
                        descriptor = descriptor,
                        activeRecord = state.record,
                        receipt = legacy,
                    )
                } ?: registry.registerGeneratedRestoredBounded(
                    descriptor = descriptor,
                    activeRecord = state.record,
                    receipt = requireNotNull(state.boundedPromotionReceipt),
                )
            }
            if (exposed) restored += 1
        }
        return restored
    }

    private fun GeneratedToolPersistentState.toCapabilityDescriptor() = CapabilityDescriptor(
        capabilityId = record.manifest.sourceCapability,
        providerId = record.manifest.toolId,
        providerType = ProviderType.GENERATED_TOOL,
        contract = CapabilityContract(record.manifest.requiredInputs, record.manifest.requiredOutputs),
        state = ProviderState.ACTIVE,
        trustLevel = TrustLevel.LOW,
        reliability = trialEvidence.stats.successRate,
        cost = 0.0,
    )
}
