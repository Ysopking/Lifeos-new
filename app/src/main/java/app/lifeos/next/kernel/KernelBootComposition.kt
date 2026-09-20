package app.lifeos.next.kernel

import app.lifeos.core.model.health.ProtectionMode
import app.lifeos.core.runtime.boot.BootCoordinator
import app.lifeos.core.runtime.boot.BootRehydrationGraph
import app.lifeos.core.runtime.boot.BootRehydrationNode
import app.lifeos.core.runtime.boot.BootRehydrationNodeId
import app.lifeos.core.runtime.boot.CapabilityWarmup
import app.lifeos.core.runtime.boot.CapabilityWarmupResult
import app.lifeos.core.runtime.boot.CognitiveHeadConsistencyDeltaSource
import app.lifeos.core.runtime.boot.CompositeBootDeltaDetector
import app.lifeos.core.runtime.boot.CompositeStoreVerifier
import app.lifeos.core.runtime.boot.DefaultBootValidator
import app.lifeos.core.runtime.boot.ModuleRehydrator
import app.lifeos.core.runtime.boot.ModuleRestoreSummary
import app.lifeos.core.runtime.boot.PhotonRehydrator
import app.lifeos.core.runtime.boot.RehydratedRuntimeState
import app.lifeos.core.runtime.boot.RuntimeBootstrapper
import app.lifeos.core.runtime.boot.StateRehydrator
import app.lifeos.core.runtime.boot.ThoughtMatrixWarmup
import app.lifeos.core.runtime.boot.ThoughtMatrixWarmupResult
import app.lifeos.core.runtime.capability.GeneratedToolState
import app.lifeos.core.runtime.capability.ProviderState
import app.lifeos.core.runtime.capability.ProviderType
import app.lifeos.core.runtime.recovery.LeaseRecoveryService

internal data class KernelBootGraph(
    val bootCoordinator: BootCoordinator,
)

/**
 * Boot/recovery composition. The ordering is intentionally unchanged from the historical
 * LifeOsKernelFactory implementation: protection/lease recovery first, then durable authority
 * rehydration, integrity verification, module/capability warmup and finally boot delta validation.
 */
internal class KernelBootComposition(
    private val foundation: KernelFoundationGraph,
    private val evolution: KernelEvolutionGraph,
    private val world: KernelWorldGraph,
    private val cognition: KernelCognitionGraph,
) {
    fun compose(): KernelBootGraph {
        fun node(
            id: String,
            dependsOn: Set<String> = emptySet(),
            action: suspend () -> Unit,
        ): BootRehydrationNode = BootRehydrationNode(
            id = BootRehydrationNodeId(id),
            dependsOn = dependsOn.mapTo(linkedSetOf(), ::BootRehydrationNodeId),
            action = action,
        )

        val rehydrationGraph = BootRehydrationGraph(
            listOf(
                node("protection") {
                    foundation.protectionCoordinator.rehydrate()
                },
                node("leases", setOf("protection")) {
                    recoverExpiredLeases(cognition.leaseRecovery)
                },
                node("extension-registry", setOf("leases")) {
                    world.extensionRegistryRehydrator.rehydrate()
                },
                node("world-equation-authority", setOf("extension-registry")) {
                    world.worldEquationAuthority.activeVersion()
                },
                node("world-equation-safety", setOf("world-equation-authority")) {
                    world.worldEquationSafetyMonitor.reconcile()
                },
                node("world-model", setOf("world-equation-authority")) {
                    val head = world.worldModelRepository.loadHead()
                    if (head != null) {
                        val snapshot = requireNotNull(
                            world.worldModelRepository.loadSnapshot(head.activeSnapshotId)
                        ) { "WorldModel head points to missing snapshot" }
                        require(snapshot.revision == head.revision) {
                            "WorldModel head/snapshot revision mismatch"
                        }
                        require(snapshot.predecessorSnapshotId == head.predecessorSnapshotId)
                    }
                },
                node("goal-plans", setOf("leases")) {
                    foundation.goalPlans.rehydrate()
                },
                node("learning-adaptations", setOf("leases")) {
                    foundation.learningAdaptations.rehydrate()
                },
                node("language-runtime", setOf("learning-adaptations")) {
                    foundation.languageRuntimeState.rehydrate()
                },
                node("thought-graph", setOf("leases")) {
                    foundation.thoughtGraph.rehydrate()
                },
                node("field-thought-projection", setOf("thought-graph")) {
                    world.fieldThoughtGraphProjection.reconcile()
                },
                node("cognition-journal-index", setOf("leases")) {
                    foundation.cognitionJournalIndex.reconcile()
                },
                node("cognitive-snapshot", setOf("cognition-journal-index")) {
                    cognition.cognitiveSnapshotManager.replay(cognition.cognitiveEventJournal)
                },
                node(
                    "cognition-reconciler",
                    setOf("cognitive-snapshot", "field-thought-projection"),
                ) {
                    cognition.cognitionReconciler.reconcile()
                },
                node("evolution-kill-switch", setOf("leases")) {
                    evolution.evolutionStore.killSwitch(BOOT_PROBE_ADOPTION_ID)
                },
                node("generated-tool-state", setOf("evolution-kill-switch")) {
                    evolution.generatedToolStateRepository.loadAll()
                },
                node("generated-artifact-verify", setOf("generated-tool-state")) {
                    evolution.privateGeneratedToolRuntime.artifactBootVerifier.verify()
                },
                node("generated-tool-rehydrate", setOf("generated-artifact-verify")) {
                    evolution.generatedToolBootRehydrator.rehydrateOrVerify()
                },
            )
        )
        val stateRehydrator = object : StateRehydrator {
            override suspend fun rehydrate(): RehydratedRuntimeState {
                rehydrationGraph.rehydrate()
                return RehydratedRuntimeState()
            }
        }

        val bootCoordinator = BootCoordinator(
            runtimeBootstrapper = object : RuntimeBootstrapper {
                override suspend fun bootstrap() = Unit
            },
            storeVerifier = CompositeStoreVerifier(
                probes = KernelBootStoreProbes(
                    bootReadSession = world.bootReadSession,
                    goalPlanRepository = foundation.goalPlanRepository,
                    learningAdaptationRepository = foundation.learningAdaptationRepository,
                    learningWatermarks = cognition.learningWatermarks,
                    worldEquationHeads = world.worldEquationHeads,
                    worldEquationSpecs = world.worldEquationSpecs,
                    worldEquationEvidence = world.worldEquationEvidence,
                    thoughtMatrixStateRepository = foundation.thoughtMatrixStateRepository,
                    thoughtGraphDeltaRepository = foundation.thoughtGraphDeltaRepository,
                    fieldThoughtGraphProjectionOutbox = world.fieldThoughtGraphProjectionOutbox,
                    protectionRepository = foundation.protectionRepository,
                    worldFormulaSnapshotRepository = world.worldFormulaSnapshotRepository,
                    productiveWorldHeadRepository = world.productiveWorldHeadRepository,
                    bootEngineCycleRepository = world.bootEngineCycleRepository,
                    extensionRegistryRehydrator = world.extensionRegistryRehydrator,
                    worldModelRepository = world.worldModelRepository,
                    cognitiveModuleSnapshotRepository = foundation.cognitiveModuleSnapshotRepository,
                    evolutionStore = evolution.evolutionStore,
                    privateGeneratedToolRuntime = evolution.privateGeneratedToolRuntime,
                ).create(),
            ),
            stateRehydrator = stateRehydrator,
            photonRehydrator = PhotonRehydrator(
                repository = foundation.store,
                journalIndex = foundation.cognitionJournalIndex,
                bootReadSession = world.bootReadSession,
            ),
            moduleRehydrator = object : ModuleRehydrator {
                override suspend fun rehydrate(): ModuleRestoreSummary {
                    foundation.matrix.rehydrate()
                    return ModuleRestoreSummary(
                        restored = foundation.registry.activeFields().size
                    )
                }
            },
            thoughtMatrixWarmup = object : ThoughtMatrixWarmup {
                override suspend fun warmup() = ThoughtMatrixWarmupResult()
            },
            capabilityWarmup = object : CapabilityWarmup {
                override suspend fun warmup(): CapabilityWarmupResult {
                    val activeGeneratedToolIds = evolution.evolutionResources.generatedTools.snapshot()
                        .filter { it.state == GeneratedToolState.ACTIVE }
                        .map { it.manifest.toolId }
                        .toSet()
                    val providers = foundation.capabilityRegistry.all(includeUnavailable = true)
                    val generatedProviderIds = providers
                        .filter { it.providerType == ProviderType.GENERATED_TOOL }
                        .map { it.providerId }
                        .toSet()
                    require(generatedProviderIds == activeGeneratedToolIds) {
                        "Generated-tool capability registry differs from rehydrated ACTIVE tool set"
                    }
                    val availableCapabilityIds = providers
                        .filter {
                            it.state == ProviderState.ACTIVE ||
                                it.state == ProviderState.DEGRADED
                        }
                        .map { it.capabilityId }
                        .toSet()
                    val degradedCapabilityIds = providers
                        .filter { it.state == ProviderState.DEGRADED }
                        .map { it.capabilityId }
                        .toSet()
                    return CapabilityWarmupResult(
                        availableCapabilities = availableCapabilityIds.size,
                        degradedCapabilities = degradedCapabilityIds.size,
                    )
                }
            },
            deltaDetector = CompositeBootDeltaDetector(
                listOf(
                    CognitiveHeadConsistencyDeltaSource(
                        worldHeads = world.productiveWorldHeadRepository,
                        activeCycleFingerprint = {
                            world.bootEngineCycleRepository.loadActive()?.fingerprint
                        },
                    )
                )
            ),
            validator = DefaultBootValidator(),
        )

        return KernelBootGraph(
            bootCoordinator = bootCoordinator,
        )
    }

    private suspend fun recoverExpiredLeases(recovery: LeaseRecoveryService) {
        while (true) {
            val result = recovery.recoverExpired(LEASE_RECOVERY_BATCH_SIZE)
            if (
                result.scanned < LEASE_RECOVERY_BATCH_SIZE ||
                result.recovered == 0
            ) {
                return
            }
        }
    }

    private companion object {
        const val BOOT_PROBE_ADOPTION_ID = "__lifeos_boot_integrity_probe__"
        const val LEASE_RECOVERY_BATCH_SIZE = 100
    }
}
