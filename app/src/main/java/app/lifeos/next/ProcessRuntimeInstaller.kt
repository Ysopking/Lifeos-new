package app.lifeos.next

import android.content.Context
import app.lifeos.core.data.artifact.EncryptedOwnerAssetReviewRepository
import app.lifeos.core.data.capability.EncryptedGeneratedToolStateRepository
import app.lifeos.core.data.deepsearch.EncryptedDeepSearchCheckpointRepository
import app.lifeos.core.data.deepsearch.EncryptedDeepSearchMissionRepository
import app.lifeos.core.data.goal.EncryptedGoalCognitiveCycleBindingRepository
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.CausalCognitionEngine
import app.lifeos.core.runtime.CausalDerivedPhotonPersistence
import app.lifeos.core.runtime.CognitiveSnapshotRuntimeRegistry
import app.lifeos.core.runtime.PhotonBackedCausalLedgerStore
import app.lifeos.core.runtime.PhotonIngressMode
import app.lifeos.core.runtime.RecursiveCausalCognitionCoordinator
import app.lifeos.core.runtime.RuntimeSupervisorProcessRegistry
import app.lifeos.core.runtime.capability.GeneratedToolRuntimeStatusReader
import app.lifeos.core.runtime.deepsearch.DeepSearchCheckpointStore
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionCoordinator
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionId
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionLedger
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionRuntimeRegistry
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionTraceRecorder
import app.lifeos.core.runtime.deepsearch.DeepSearchResultPhotonPersistence
import app.lifeos.core.runtime.evolution.NovelPromotionRuntimeEventRegistry
import app.lifeos.core.runtime.health.HealthGraphProcessRegistry
import app.lifeos.core.runtime.health.ProtectionCoordinatorProcessRegistry
import app.lifeos.core.runtime.health.QuarantineRegistryProcessRegistry
import app.lifeos.core.runtime.life.DomainEvidenceConvergenceCoordinator
import app.lifeos.core.runtime.life.DomainEvidenceConvergingPersistence
import app.lifeos.core.runtime.life.DurableLifeMemoryRuntime
import app.lifeos.core.runtime.life.DurableLifeMemoryRuntimeRegistry
import app.lifeos.core.runtime.life.FuturePlanningCoordinator
import app.lifeos.core.runtime.life.FuturePlanningPersistence
import app.lifeos.core.runtime.life.LifeOsIntegratedCognitionSuite
import app.lifeos.core.runtime.life.LifeOsIntegratedCognitionSuiteRegistry
import app.lifeos.core.runtime.policy.OwnerObservationPolicyLedger
import app.lifeos.core.runtime.policy.OwnerPolicyLedger
import app.lifeos.core.runtime.resource.ResourceBudgetCoordinator
import app.lifeos.core.runtime.self.SelfObservationDecisionTraceRecorder
import app.lifeos.core.runtime.trace.DecisionTraceLedger
import app.lifeos.core.runtime.trace.GoalDecisionTraceRecorder
import app.lifeos.core.runtime.workers.CausalCognitionTaskObserver
import app.lifeos.core.runtime.workers.CausalCognitionTaskObserverRegistry
import app.lifeos.next.kernel.CanonicalLifePhotonRepository
import app.lifeos.next.kernel.CanonicalPhotonIngress
import app.lifeos.next.kernel.DurableGoalPlanRuntime
import app.lifeos.next.kernel.DurableGoalPlanRuntimeRegistry
import app.lifeos.next.kernel.GoalExecutionRuntimeRegistry
import app.lifeos.next.kernel.HardwareResourceIntelligenceRuntime
import app.lifeos.next.kernel.LifeOsAutomationPhotonBridge
import app.lifeos.next.kernel.LifeOsHealthPhotonBridge
import app.lifeos.next.kernel.LifeOsKernel
import app.lifeos.next.kernel.LifeOsKernelFactory
import app.lifeos.next.kernel.MultimodalPerceptionRuntime
import app.lifeos.next.kernel.PrivateEscalationRuntime
import app.lifeos.next.kernel.PrivateFuturePlanningAuthority
import app.lifeos.next.kernel.PrivateGoalActionExecutionGuard
import app.lifeos.next.kernel.PrivateSelfHealingRuntime
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

internal data class ProcessRuntimeInstallResult(
    val kernel: LifeOsKernel,
    val photonIngress: CanonicalPhotonIngress,
    val generatedToolStatusReader: GeneratedToolRuntimeStatusReader,
    val hardwareResourceIntelligence: HardwareResourceIntelligenceRuntime,
    val storageIntelligence: AndroidStorageIntelligenceRuntime,
    val storageMaintenance: AndroidStorageMaintenanceRuntime,
    val storageIntelligenceController: StorageIntelligenceProcessController,
    val ownerPolicy: OwnerPolicyLedger,
    val ownerObservationPolicy: OwnerObservationPolicyLedger,
    val resourceBudgets: ResourceBudgetCoordinator,
    val decisionTraces: DecisionTraceLedger,
    val selfObservationDecisionTraceRecorder: SelfObservationDecisionTraceRecorder,
    val goalDecisionTraceRecorder: GoalDecisionTraceRecorder,
    val lifePhotonRepository: CanonicalLifePhotonRepository,
    val lifeMemoryRuntime: DurableLifeMemoryRuntime,
    val multimodalPerception: MultimodalPerceptionRuntime,
    val selfHealingRuntime: PrivateSelfHealingRuntime,
    val escalationRuntime: PrivateEscalationRuntime,
)

internal class ProcessRuntimeInstaller(
    context: Context,
    private val onSelfObservationRequested:
        (app.lifeos.core.runtime.self.SelfObservationTrigger) -> Unit,
    private val canRunStorageIntelligence: () -> Boolean,
    private val onStorageSnapshot: (StorageIntelligenceSnapshot) -> Unit,
    private val onStorageFailure: (String?) -> Unit,
    private val onStageReady: (LifeOsStartupStageEvidence) -> Unit,
) {
    private val appContext = context.applicationContext
    private val selfHealingScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun install(): ProcessRuntimeInstallResult {
        val generatedToolStatusReader = GeneratedToolRuntimeStatusReader(
            EncryptedGeneratedToolStateRepository(appContext),
        )
        val hardwareResourceIntelligence =
            HardwareResourceIntelligenceRuntime(appContext)
        val storageIntelligence = AndroidStorageIntelligenceRuntime(
            context = appContext,
            hardware = hardwareResourceIntelligence,
        )
        val storageMaintenance = AndroidStorageMaintenanceRuntime(
            context = appContext,
            hardware = hardwareResourceIntelligence,
        )
        val storageIntelligenceController = StorageIntelligenceProcessController(
            storage = storageIntelligence,
            hardware = hardwareResourceIntelligence,
            canRun = canRunStorageIntelligence,
            onSnapshot = onStorageSnapshot,
            onFailure = onStorageFailure,
        )
        LifeOsIntegratedCognitionSuiteRegistry.install(
            LifeOsIntegratedCognitionSuite()
        )

        lateinit var ownerPolicy: OwnerPolicyLedger
        lateinit var ownerObservationPolicy: OwnerObservationPolicyLedger
        lateinit var resourceBudgets: ResourceBudgetCoordinator
        lateinit var decisionTraces: DecisionTraceLedger
        lateinit var selfObservationDecisionTraceRecorder:
            SelfObservationDecisionTraceRecorder
        lateinit var goalDecisionTraceRecorder: GoalDecisionTraceRecorder
        lateinit var kernel: LifeOsKernel
        lateinit var photonIngress: CanonicalPhotonIngress
        lateinit var lifePhotonRepository: CanonicalLifePhotonRepository
        lateinit var lifeMemoryRuntime: DurableLifeMemoryRuntime
        lateinit var multimodalPerception: MultimodalPerceptionRuntime
        lateinit var selfHealingRuntime: PrivateSelfHealingRuntime
        lateinit var escalationRuntime: PrivateEscalationRuntime

        LifeOsStartupComposition.start(
            LifeOsStartupHooks(
                installSharedResourceRuntime = {
                    val shared = SharedAuthorityRuntimeComposition.install(
                        context = appContext,
                        hardware = hardwareResourceIntelligence,
                    )
                    ownerPolicy = shared.ownerPolicy
                    ownerObservationPolicy = shared.ownerObservationPolicy
                    resourceBudgets = shared.resourceBudgets
                    decisionTraces = shared.decisionTraces
                    selfObservationDecisionTraceRecorder =
                        shared.selfObservationDecisionTraceRecorder
                    goalDecisionTraceRecorder =
                        shared.goalDecisionTraceRecorder
                },
                installGoalExecutionRuntime = {
                    GoalExecutionRuntimeRegistry.install(
                        PrivateGoalActionExecutionGuard(
                            ownerPolicy = ownerPolicy,
                            budgets = resourceBudgets,
                            hardware = hardwareResourceIntelligence,
                            sharedBudgets = hardwareResourceIntelligence,
                            traces = goalDecisionTraceRecorder,
                        )
                    )
                },
                createKernel = {
                    kernel = LifeOsKernelFactory(
                        context = appContext,
                        hardwareResourceIntelligence =
                            hardwareResourceIntelligence,
                        bootReadyMaintenanceTrigger =
                            storageIntelligenceController::refresh,
                    ).create()

                    val ownerAssetReviews =
                        EncryptedOwnerAssetReviewRepository(appContext)
                    photonIngress =
                        CanonicalPhotonIngress(
                            kernel = kernel,
                            ownerAssetReviews = ownerAssetReviews,
                            ownerObservationPolicy = ownerObservationPolicy,
                        )
                    lifePhotonRepository = CanonicalLifePhotonRepository(
                        delegate = kernel.photonStore,
                        productiveIngress = photonIngress::ingest,
                    )
                    lifeMemoryRuntime =
                        DurableLifeMemoryRuntime(lifePhotonRepository)
                    DurableLifeMemoryRuntimeRegistry.install(lifeMemoryRuntime)

                    multimodalPerception = MultimodalPerceptionRuntime(kernel)
                    multimodalPerception.install()

                    val integratedCognition = requireNotNull(
                        LifeOsIntegratedCognitionSuiteRegistry.current()
                    ) {
                        "Integrated cognition suite must be installed before kernel composition"
                    }
                    val basePersistence =
                        CausalDerivedPhotonPersistence { derived, _ ->
                            photonIngress.ingest(
                                derived,
                                PhotonIngressMode.DERIVED,
                            )
                        }
                    val domainPersistence =
                        DomainEvidenceConvergingPersistence(
                            delegate = basePersistence,
                            convergence =
                                DomainEvidenceConvergenceCoordinator(
                                    kernel.photonStore
                                ),
                        )
                    val futurePlanning = FuturePlanningCoordinator(
                        photons = kernel.photonStore,
                        authority = PrivateFuturePlanningAuthority(
                            ownerPolicy = ownerPolicy,
                            resources = hardwareResourceIntelligence,
                        ),
                        planner = integratedCognition.lifePlanner,
                        evaluator = integratedCognition.seinEvaluator,
                    )
                    val productivePersistence = FuturePlanningPersistence(
                        delegate = domainPersistence,
                        planning = futurePlanning,
                    )

                    lifePhotonRepository.reconcilePersisted()
                    lifeMemoryRuntime.rebuild(Instant.now())
                    CognitiveSnapshotRuntimeRegistry.captureLatest()
                    futurePlanning.reconsiderAll().forEach { planned ->
                        photonIngress.ingest(
                            planned,
                            PhotonIngressMode.DERIVED,
                        )
                    }

                    val frozenCognitiveModules =
                        kernel.freezeCognitiveModulesForCurrentCycle(
                            integratedCognition.domainModules
                        )
                    val causalCoordinator =
                        RecursiveCausalCognitionCoordinator(
                            modules = frozenCognitiveModules,
                            engine = CausalCognitionEngine(
                                ledger = PhotonBackedCausalLedgerStore(
                                    kernel.photonStore
                                ),
                            ),
                            persistence = productivePersistence,
                        )
                    CausalCognitionTaskObserverRegistry.install(
                        CausalCognitionTaskObserver(
                            photons = kernel.photonStore,
                            cognition = causalCoordinator,
                        )
                    )
                    LifeOsAutomationPhotonBridge.install { photon ->
                        photonIngress.ingest(
                            photon,
                            PhotonIngressMode.ORIGIN,
                        )
                        photon
                    }
                    NovelPromotionRuntimeEventRegistry.install { promotion ->
                        val capability =
                            promotion.activeRecord.manifest.sourceCapability.value
                        val toolId =
                            promotion.activeRecord.manifest.toolId
                        photonIngress.ingest(
                            Photon(
                                content =
                                    "Controlled Evolution aktiviert $capability über $toolId nach " +
                                        "Novel-Canary-, Readiness-, Owner- und Promotion-Gates.",
                                provenance = Provenance(
                                    source = "controlled-evolution",
                                    actor = "system",
                                    createdAt = promotion.seal.sealedAt,
                                ),
                                tags = setOf(
                                    "chat",
                                    "chat:system",
                                    "conversation:default",
                                    "system:evolution",
                                    "evolution:activated",
                                    "capability:$capability",
                                    "tool:$toolId",
                                ),
                            ),
                            PhotonIngressMode.ORIGIN,
                        )
                        onSelfObservationRequested(
                            app.lifeos.core.runtime.self
                                .SelfObservationTrigger.TOOL_STATE_TRANSITION
                        )
                        Unit
                    }
                },
                installDeepSearchRuntime = {
                    DeepSearchMissionRuntimeRegistry.install(
                        DeepSearchMissionCoordinator(
                            ledger = DeepSearchMissionLedger(
                                EncryptedDeepSearchMissionRepository(appContext)
                            ),
                            checkpoints = DeepSearchCheckpointStore(
                                EncryptedDeepSearchCheckpointRepository(
                                    appContext
                                )
                            ),
                            traces = DeepSearchMissionTraceRecorder { definition, product ->
                                DecisionTraceRuntimeRegistry.currentOrNull()?.recordDeepSearch(
                                    goalPhotonId = definition.goalPhotonId,
                                    goalPhotonRevision = 1L,
                                    recordedAt = definition.createdAt,
                                    result = product.result,
                                    missionId = definition.id,
                                )
                            },
                            resultPhotons =
                                object : DeepSearchResultPhotonPersistence {
                                    override suspend fun save(photon: Photon) {
                                        photonIngress.ingest(
                                            photon,
                                            PhotonIngressMode.DERIVED,
                                        )
                                    }

                                    override suspend fun load(
                                        id: PhotonId,
                                    ): Photon? =
                                        kernel.photonStore.load(id)

                                    override suspend fun findForMission(
                                        missionId: DeepSearchMissionId,
                                    ): Photon? {
                                        val tag =
                                            "deepsearch-mission:${missionId.value}"
                                        val matches =
                                            kernel.productivePhotonQueries.tags(
                                                allTags = setOf(tag),
                                                limit = 2,
                                            ).photons
                                        check(matches.size <= 1) {
                                            "DeepSearch mission resolved to multiple result Photons"
                                        }
                                        return matches.singleOrNull()
                                    }
                                },
                        )
                    )
                },
                startSelfHealingRuntime = {
                    val healthGraph = requireNotNull(
                        HealthGraphProcessRegistry.current()
                    ) {
                        "Kernel did not install its HealthGraph"
                    }
                    val quarantineRegistry = requireNotNull(
                        QuarantineRegistryProcessRegistry.current()
                    ) {
                        "Kernel did not install its QuarantineRegistry"
                    }
                    val supervisor = requireNotNull(
                        RuntimeSupervisorProcessRegistry.current()
                    ) {
                        "Kernel did not install its RuntimeSupervisor"
                    }
                    val protectionCoordinator = requireNotNull(
                        ProtectionCoordinatorProcessRegistry.current()
                    ) {
                        "Kernel did not install its ProtectionCoordinator"
                    }
                    selfHealingRuntime = PrivateSelfHealingRuntime.create(
                        context = appContext,
                        scope = selfHealingScope,
                        graph = healthGraph,
                        quarantineRegistry = quarantineRegistry,
                        budgets = resourceBudgets,
                        runtime = kernel.runtime,
                        supervisor = supervisor,
                    )
                    escalationRuntime = PrivateEscalationRuntime.create(
                        context = appContext,
                        scope = selfHealingScope,
                        graph = healthGraph,
                        protection = protectionCoordinator,
                        selfHealing = selfHealingRuntime,
                    )
                    selfHealingRuntime.verifyLedgerIntegrity()
                    escalationRuntime.verifyLedgerIntegrity()
                    LifeOsHealthPhotonBridge.start(
                        scope = selfHealingScope,
                        graph = healthGraph,
                        persist = { photon ->
                            photonIngress.ingest(
                                photon,
                                PhotonIngressMode.ORIGIN,
                            )
                            Unit
                        },
                    )
                    escalationRuntime.orchestrator.start()
                },
                installDurableGoalPlanRuntime = {
                    DurableGoalPlanRuntimeRegistry.install(
                        DurableGoalPlanRuntime(
                            ledger = kernel.goalPlans,
                            convergence = kernel.productiveGoalConvergence,
                            persistDerivedOutcome = { photon ->
                                photonIngress.ingestWithReceipt(
                                    photon,
                                    PhotonIngressMode.DERIVED,
                                )
                            },
                            outcomeLookup = kernel.productivePhotonQueries,
                            cognitiveBindings =
                                EncryptedGoalCognitiveCycleBindingRepository(
                                    appContext
                                ),
                            outcomeLearning = kernel.goalOutcomeLearning,
                            traces = goalDecisionTraceRecorder,
                        )
                    )
                },
                startKernel = {
                    kernel.start().join()
                },
                requireCognitiveStateReady = {
                    kernel.requireCognitiveReady()
                },
                stageObserver = { evidence ->
                    LifeOsRuntimeWiring.onStageReady(evidence)
                    onStageReady(evidence)
                },
            )
        )

        return ProcessRuntimeInstallResult(
            kernel = kernel,
            photonIngress = photonIngress,
            generatedToolStatusReader = generatedToolStatusReader,
            hardwareResourceIntelligence = hardwareResourceIntelligence,
            storageIntelligence = storageIntelligence,
            storageMaintenance = storageMaintenance,
            storageIntelligenceController = storageIntelligenceController,
            ownerPolicy = ownerPolicy,
            ownerObservationPolicy = ownerObservationPolicy,
            resourceBudgets = resourceBudgets,
            decisionTraces = decisionTraces,
            selfObservationDecisionTraceRecorder =
                selfObservationDecisionTraceRecorder,
            goalDecisionTraceRecorder = goalDecisionTraceRecorder,
            lifePhotonRepository = lifePhotonRepository,
            lifeMemoryRuntime = lifeMemoryRuntime,
            multimodalPerception = multimodalPerception,
            selfHealingRuntime = selfHealingRuntime,
            escalationRuntime = escalationRuntime,
        )
    }
}
