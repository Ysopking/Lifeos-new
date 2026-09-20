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
        val protection = BootRehydrationNodeId("protection")
        val leases = BootRehydrationNodeId("leases")
        val extensionRegistry = BootRehydrationNodeId("extension-registry")
        val worldEquationAuthority = BootRehydrationNodeId("world-equation-authority")
        val worldEquationSafety = BootRehydrationNodeId("world-equation-safety")
        val worldModel = BootRehydrationNodeId("world-model")
        val goalPlans = BootRehydrationNodeId("goal-plans")
        val learningAdaptations = BootRehydrationNodeId("learning-adaptations")
        val languageRuntime = BootRehydrationNodeId("language-runtime")
        val thoughtGraph = BootRehydrationNodeId("thought-graph")
        val fieldThoughtProjection = BootRehydrationNodeId("field-thought-projection")
        val cognitionJournalIndex = BootRehydrationNodeId("cognition-journal-index")
        val cognitiveSnapshot = BootRehydrationNodeId("cognitive-snapshot")
        val cognitionReconciler = BootRehydrationNodeId("cognition-reconciler")
        val evolutionKillSwitch = BootRehydrationNodeId("evolution-kill-switch")
        val generatedToolState = BootRehydrationNodeId("generated-tool-state")
        val generatedArtifactVerify = BootRehydrationNodeId("generated-artifact-verify")
        val generatedToolRehydrate = BootRehydrationNodeId("generated-tool-rehydrate")

        val stateRehydrator = BootRehydrationGraph(
            nodes = listOf(
                BootRehydrationNode(protection) {
                    foundation.protectionCoordinator.rehydrate()
                },
                BootRehydrationNode(
                    id = leases,
                    dependsOn = setOf(protection),
                ) {
                    recoverExpiredLeases(cognition.leaseRecovery)
                },
                BootRehydrationNode(
                    id = extensionRegistry,
                    dependsOn = setOf(leases),
                ) {
                    world.extensionRegistryRehydrator.rehydrate()
                },
                BootRehydrationNode(
                    id = worldEquationAuthority,
                    dependsOn = setOf(extensionRegistry),
                ) {
                    world.worldEquationAuthority.activeVersion()
                },
                BootRehydrationNode(
                    id = worldEquationSafety,
                    dependsOn = setOf(worldEquationAuthority),
                ) {
                    world.worldEquationSafetyMonitor.reconcile()
                },
                BootRehydrationNode(
                    id = worldModel,
                    dependsOn = setOf(worldEquationAuthority),
                ) {
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
                BootRehydrationNode(
                    id = goalPlans,
                    dependsOn = setOf(leases),
                ) {
                    foundation.goalPlans.rehydrate()
                },
                BootRehydrationNode(
                    id = learningAdaptations,
                    dependsOn = setOf(leases),
                ) {
                    foundation.learningAdaptations.rehydrate()
                },
                BootRehydrationNode(
                    id = languageRuntime,
                    dependsOn = setOf(learningAdaptations),
                ) {
                    foundation.languageRuntimeState.rehydrate()
                },
                BootRehydrationNode(
                    id = thoughtGraph,
                    dependsOn = setOf(leases),
                ) {
                    foundation.thoughtGraph.rehydrate()
                },
                BootRehydrationNode(
                    id = fieldThoughtProjection,
                    dependsOn = setOf(thoughtGraph),
                ) {
                    world.fieldThoughtGraphProjection.reconcile()
                },
                BootRehydrationNode(
                    id = cognitionJournalIndex,
                    dependsOn = setOf(leases),
                ) {
                    foundation.cognitionJournalIndex.reconcile()
                },
                BootRehydrationNode(
                    id = cognitiveSnapshot,
                    dependsOn = setOf(cognitionJournalIndex),
                ) {
                    cognition.cognitiveSnapshotManager.replay(cognition.cognitiveEventJournal)
                },
                BootRehydrationNode(
                    id = cognitionReconciler,
                    dependsOn = setOf(cognitiveSnapshot, fieldThoughtProjection),
                ) {
                    cognition.cognitionReconciler.reconcile()
                },
                BootRehydrationNode(
                    id = evolutionKillSwitch,
                    dependsOn = setOf(leases),
                ) {
                    evolution.evolutionStore.killSwitch(BOOT_PROBE_ADOPTION_ID)
                },
                BootRehydrationNode(
                    id = generatedToolState,
                    dependsOn = setOf(evolutionKillSwitch),
                ) {
                    evolution.generatedToolStateRepository.loadAll()
                },
                BootRehydrationNode(
                    id = generatedArtifactVerify,
                    dependsOn = setOf(generatedToolState),
                ) {
                    evolution.privateGeneratedToolRuntime.artifactBootVerifier.verify()
                },
                BootRehydrationNode(
                    id = generatedToolRehydrate,
                    dependsOn = setOf(generatedArtifactVerify),
                ) {
                    evolution.generatedToolBootRehydrator.rehydrateOrVerify()
                },
            ),
            restoredState = { RehydratedRuntimeState() },
        )

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
