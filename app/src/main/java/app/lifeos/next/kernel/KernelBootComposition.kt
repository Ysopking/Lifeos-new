package app.lifeos.next.kernel

import app.lifeos.core.model.health.ProtectionMode
import app.lifeos.core.runtime.boot.BootCoordinator
import app.lifeos.core.runtime.boot.BootCriticality
import app.lifeos.core.runtime.boot.BootRehydrationGraph
import app.lifeos.core.runtime.boot.BootRehydrationNode
import app.lifeos.core.runtime.boot.BootRehydrationNodeId
import app.lifeos.core.runtime.boot.BootRehydrationReport
import app.lifeos.core.runtime.boot.CapabilityWarmupResult
import app.lifeos.core.runtime.boot.CognitiveHeadConsistencyDeltaSource
import app.lifeos.core.runtime.boot.CompositeBootDeltaDetector
import app.lifeos.core.runtime.boot.CompositeStoreVerifier
import app.lifeos.core.runtime.boot.DefaultBootValidator
import app.lifeos.core.runtime.boot.ModuleRehydrator
import app.lifeos.core.runtime.boot.ModuleRestoreSummary
import app.lifeos.core.runtime.boot.PhotonRehydrator
import app.lifeos.core.runtime.boot.RehydratedRuntimeState
import app.lifeos.core.runtime.boot.RegistryCapabilityWarmup
import app.lifeos.core.runtime.boot.RuntimeBootstrapper
import app.lifeos.core.runtime.boot.StateRehydrator
import app.lifeos.core.runtime.boot.ThoughtMatrixWarmup
import app.lifeos.core.runtime.boot.ThoughtMatrixWarmupResult
import app.lifeos.core.runtime.boot.WarmRuntimeFeature
import app.lifeos.core.runtime.boot.WarmRuntimeReadinessRegistry
import app.lifeos.core.runtime.capability.ProviderType
import app.lifeos.core.runtime.recovery.LeaseRecoveryService

internal data class KernelBootGraph(
    val bootCoordinator: BootCoordinator,
    val warmRehydrator: suspend () -> BootRehydrationReport,
    val warmPhotonRehydrator: suspend () -> app.lifeos.core.runtime.boot.PhotonRehydrationResult,
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
            criticality: BootCriticality,
            action: suspend () -> Unit,
        ): BootRehydrationNode = BootRehydrationNode(
            id = BootRehydrationNodeId(id),
            dependsOn = dependsOn.mapTo(linkedSetOf(), ::BootRehydrationNodeId),
            criticality = criticality,
            action = action,
        )

        val storeProbes = KernelBootStoreProbes(
            bootReadSession = world.bootReadSession,
            photonIndexReport = foundation.store::indexReport,
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
        ).create()
        val criticalStoreProbes =
            storeProbes.filter { it.criticality != BootCriticality.OPTIONAL_WARM }
        val optionalStoreProbes =
            storeProbes.filter { it.criticality == BootCriticality.OPTIONAL_WARM }

        val criticalRehydrationGraph = BootRehydrationGraph(
            listOf(
                node("protection", criticality = BootCriticality.SECURE_REQUIRED) {
                    foundation.protectionCoordinator.rehydrate()
                },
                node("leases", setOf("protection"), BootCriticality.REQUIRED_DEGRADED) {
                    recoverExpiredLeases(cognition.leaseRecovery)
                },
                node("extension-registry", setOf("leases"), BootCriticality.REQUIRED_DEGRADED) {
                    world.extensionRegistryRehydrator.rehydrate()
                },
                node(
                    "world-equation-authority",
                    setOf("extension-registry"),
                    BootCriticality.REQUIRED_DEGRADED,
                ) {
                    world.worldEquationAuthority.activeVersion()
                },
                node(
                    "world-equation-safety",
                    setOf("world-equation-authority"),
                    BootCriticality.REQUIRED_DEGRADED,
                ) {
                    world.worldEquationSafetyMonitor.reconcile()
                },
                node(
                    "world-model",
                    setOf("world-equation-authority"),
                    BootCriticality.REQUIRED_DEGRADED,
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
                node("learning-adaptations", setOf("leases"), BootCriticality.REQUIRED_DEGRADED) {
                    foundation.learningAdaptations.rehydrate()
                },
                node(
                    "language-runtime",
                    setOf("learning-adaptations"),
                    BootCriticality.REQUIRED_DEGRADED,
                ) {
                    foundation.languageRuntimeState.rehydrate()
                },
                node(
                    "cognition-journal-index",
                    setOf("leases"),
                    BootCriticality.REQUIRED_DEGRADED,
                ) {
                    foundation.cognitionJournalIndex.reconcile()
                },
                node(
                    "cognitive-snapshot",
                    setOf("cognition-journal-index"),
                    BootCriticality.REQUIRED_DEGRADED,
                ) {
                    cognition.cognitiveSnapshotManager.replay(cognition.cognitiveEventJournal)
                },
            )
        )

        val warmRehydrationGraph = BootRehydrationGraph(
            listOf(
                node(
                    "optional-store-integrity",
                    criticality = BootCriticality.OPTIONAL_WARM,
                ) {
                    val verification = CompositeStoreVerifier(optionalStoreProbes).verify()
                    val failures = verification.stores.filter { it.state != app.lifeos.core.runtime.boot.StoreState.HEALTHY }
                    check(failures.isEmpty()) {
                        "Optional store verification failed: " +
                            failures.joinToString(",") { "${it.storeId}:${it.state.name}" }
                    }
                },
                node("goal-plans", criticality = BootCriticality.OPTIONAL_WARM) {
                    foundation.goalPlans.rehydrate()
                },
                node("thought-graph", criticality = BootCriticality.OPTIONAL_WARM) {
                    foundation.thoughtGraph.rehydrate()
                },
                node(
                    "field-thought-projection",
                    setOf("thought-graph"),
                    BootCriticality.OPTIONAL_WARM,
                ) {
                    world.fieldThoughtGraphProjection.reconcile()
                },
                node(
                    "cognition-reconciler",
                    setOf("field-thought-projection"),
                    BootCriticality.OPTIONAL_WARM,
                ) {
                    cognition.cognitionReconciler.reconcile()
                },
                node("evolution-kill-switch", criticality = BootCriticality.OPTIONAL_WARM) {
                    evolution.evolutionStore.killSwitch(BOOT_PROBE_ADOPTION_ID)
                },
                node(
                    "generated-tool-state",
                    setOf("evolution-kill-switch"),
                    BootCriticality.OPTIONAL_WARM,
                ) {
                    evolution.generatedToolStateRepository.loadAll()
                },
                node(
                    "generated-artifact-verify",
                    setOf("generated-tool-state"),
                    BootCriticality.OPTIONAL_WARM,
                ) {
                    evolution.privateGeneratedToolRuntime.artifactBootVerifier.verify()
                },
                node(
                    "generated-tool-rehydrate",
                    setOf("generated-artifact-verify"),
                    BootCriticality.OPTIONAL_WARM,
                ) {
                    WarmRuntimeReadinessRegistry.runWarmup(
                        WarmRuntimeFeature.GENERATED_TOOLS
                    ) {
                        evolution.generatedToolBootRehydrator.rehydrateOrVerify()
                    }
                },
            )
        )

        val stateRehydrator = object : StateRehydrator {
            override suspend fun rehydrate(): RehydratedRuntimeState {
                val report = criticalRehydrationGraph.rehydrate()
                return RehydratedRuntimeState(
                    degradedRehydrationNodeIds =
                        report.degradedNodeIds.map { it.value },
                )
            }
        }

        val photonRehydrator = PhotonRehydrator(
            repository = foundation.store,
            journalIndex = foundation.cognitionJournalIndex,
            bootReadSession = world.bootReadSession,
            criticalHydration = true,
        )

        val bootCoordinator = BootCoordinator(
            runtimeBootstrapper = object : RuntimeBootstrapper {
                override suspend fun bootstrap() = Unit
            },
            storeVerifier = CompositeStoreVerifier(
                probes = criticalStoreProbes,
            ),
            stateRehydrator = stateRehydrator,
            photonRehydrator = photonRehydrator,
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
            capabilityWarmup = RegistryCapabilityWarmup(
                registry = foundation.capabilityRegistry,
                excludedProviderTypes = setOf(ProviderType.GENERATED_TOOL),
            ),
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
            warmRehydrator = warmRehydrationGraph::rehydrate,
            warmPhotonRehydrator = photonRehydrator::rehydrateAll,
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
