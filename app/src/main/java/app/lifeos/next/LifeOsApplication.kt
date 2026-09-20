package app.lifeos.next

import android.app.Application
import app.lifeos.core.data.goal.EncryptedGoalCognitiveCycleBindingRepository
import app.lifeos.core.data.LiveSourceSyncSnapshot
import app.lifeos.core.data.artifact.EncryptedOwnerAssetReviewRepository
import app.lifeos.core.data.capability.EncryptedGeneratedToolStateRepository
import app.lifeos.core.runtime.policy.OwnerPolicyEffectGate
import app.lifeos.core.runtime.agency.PolicyGatedExternalEffectExecutor
import app.lifeos.core.runtime.agency.ExternalTransportRuntimeRegistry
import app.lifeos.core.runtime.agency.ExternalEffectRuntimeRegistry
import app.lifeos.core.data.agency.EncryptedExternalEffectReceiptRepository
import app.lifeos.core.data.agency.EncryptedExternalPayloadRepository
import app.lifeos.core.data.deepsearch.EncryptedDeepSearchCheckpointRepository
import app.lifeos.core.data.deepsearch.EncryptedDeepSearchMissionRepository
import app.lifeos.core.data.policy.EncryptedOwnerPolicyRepository
import app.lifeos.core.data.resource.EncryptedResourceBudgetRepository
import app.lifeos.core.data.trace.EncryptedDecisionTraceRepository
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.CausalCognitionEngine
import app.lifeos.core.runtime.CausalDerivedPhotonPersistence
import app.lifeos.core.runtime.PhotonBackedCausalLedgerStore
import app.lifeos.core.runtime.PhotonIngressMode
import app.lifeos.core.runtime.RecursiveCausalCognitionCoordinator
import app.lifeos.core.runtime.RuntimeSupervisorProcessRegistry
import app.lifeos.core.runtime.capability.GeneratedProviderRestoreAuthority
import app.lifeos.core.runtime.capability.GeneratedProviderRestoreAuthorityRuntimeRegistry
import app.lifeos.core.runtime.capability.GeneratedToolRuntimeStatusReader
import app.lifeos.core.runtime.deepsearch.DeepSearchCheckpointStore
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionCoordinator
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionId
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionLedger
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionRuntimeRegistry
import app.lifeos.core.runtime.deepsearch.DeepSearchResultPhotonPersistence
import app.lifeos.core.runtime.evolution.NovelPromotionRuntimeEventRegistry
import app.lifeos.core.runtime.evolution.WorldEquationPostActivationSafetyRuntimeRegistry
import app.lifeos.core.runtime.health.HealthGraphProcessRegistry
import app.lifeos.core.runtime.health.ProtectionCoordinatorProcessRegistry
import app.lifeos.core.runtime.health.QuarantineRegistryProcessRegistry
import app.lifeos.core.runtime.life.DomainEvidenceConvergenceCoordinator
import app.lifeos.core.runtime.life.DomainEvidenceConvergingPersistence
import app.lifeos.core.runtime.CognitiveSnapshotRuntimeRegistry
import app.lifeos.core.runtime.life.DurableLifeMemoryRuntime
import app.lifeos.core.runtime.life.DurableLifeMemoryRuntimeRegistry
import app.lifeos.core.runtime.life.FuturePlanningCoordinator
import app.lifeos.core.runtime.life.FuturePlanningPersistence
import app.lifeos.core.runtime.life.InitialDataBootstrapRuntime
import app.lifeos.core.runtime.life.InitialDataBootstrapSnapshot
import app.lifeos.core.runtime.life.LifeOsIntegratedCognitionSuite
import app.lifeos.core.runtime.life.LifeOsIntegratedCognitionSuiteRegistry
import app.lifeos.core.runtime.policy.OwnerPolicyLedger
import app.lifeos.core.runtime.resource.ResourceBudgetCoordinator
import app.lifeos.core.runtime.resource.SharedResourceBudgetRuntimeRegistry
import app.lifeos.core.runtime.self.SelfObservationAuthorityRuntimeRegistry
import app.lifeos.core.runtime.self.SelfObservationCapture
import app.lifeos.core.runtime.self.SelfObservationCoordinator
import app.lifeos.core.runtime.self.SelfObservationCycle
import app.lifeos.core.runtime.self.SELF_OBSERVATION_HEALTH_NODE_ID
import app.lifeos.core.runtime.self.SELF_OBSERVATION_HEALTH_SOURCE
import app.lifeos.core.runtime.self.SelfObservationDecisionTraceRecorder
import app.lifeos.core.runtime.self.SelfObservationTrigger
import app.lifeos.core.runtime.topology.LifeOsProcessTopology
import app.lifeos.core.runtime.world.SelfStateWorldBand
import app.lifeos.core.runtime.world.SelfStateWorldFormulaAssessment
import app.lifeos.core.runtime.world.SelfStateWorldFormulaRuntimeRegistry
import app.lifeos.core.runtime.trace.DecisionTraceLedger
import app.lifeos.core.runtime.trace.DecisionTraceRuntimeRegistry
import app.lifeos.core.runtime.trace.GoalDecisionTraceRecorder
import app.lifeos.core.runtime.trace.LifecycleDecisionTraceRecorder
import app.lifeos.core.runtime.trace.LifecycleDecisionTraceRuntimeRegistry
import app.lifeos.core.runtime.trace.SubsystemDecisionTraceRecorder
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
import app.lifeos.next.kernel.PrivateOwnerPolicyBaseline
import app.lifeos.next.kernel.PrivateSelfHealingRuntime
import app.lifeos.next.kernel.SelfObservationRuntime
import app.lifeos.next.kernel.WebDeepSearchRuntime
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SelfObservationAnalysisState(
    val cycle: SelfObservationCycle,
    val assessment: SelfStateWorldFormulaAssessment,
)

/** Process-level owner for the LIFEOS kernel instance and read-only private diagnostics. */
class LifeOsApplication : Application(), LifeOsProcessStartupStateReader {
    lateinit var kernel: LifeOsKernel
        private set

    lateinit var photonIngress: CanonicalPhotonIngress
        private set

    lateinit var generatedToolStatusReader: GeneratedToolRuntimeStatusReader
        private set

    lateinit var hardwareResourceIntelligence: HardwareResourceIntelligenceRuntime
        private set

    internal lateinit var storageIntelligence: AndroidStorageIntelligenceRuntime
        private set

    internal lateinit var storageMaintenance: AndroidStorageMaintenanceRuntime
        private set

    internal lateinit var selfObservationRuntime: SelfObservationRuntime
        private set

    lateinit var selfObservationCoordinator: SelfObservationCoordinator
        private set

    lateinit var ownerPolicy: OwnerPolicyLedger
        private set

    lateinit var resourceBudgets: ResourceBudgetCoordinator
        private set

    lateinit var decisionTraces: DecisionTraceLedger
        private set

    private lateinit var selfObservationDecisionTraceRecorder: SelfObservationDecisionTraceRecorder

    lateinit var lifeMemoryRuntime: DurableLifeMemoryRuntime
        private set

    lateinit var multimodalPerception: MultimodalPerceptionRuntime
        private set

    lateinit var initialDataBootstrap: InitialDataBootstrapRuntime
        private set

    @Volatile
    var latestInitialDataBootstrap: InitialDataBootstrapSnapshot? = null
        private set

    @Volatile
    var initialDataBootstrapFailure: String? = null
        private set

    @Volatile
    var latestLiveSourceSync: LiveSourceSyncSnapshot? = null
        private set

    @Volatile
    var liveSourceSyncFailure: String? = null
        private set

    @Volatile
    internal var latestStorageIntelligence: StorageIntelligenceSnapshot? = null
        private set

    @Volatile
    var storageIntelligenceFailure: String? = null
        private set

    internal lateinit var selfHealingRuntime: PrivateSelfHealingRuntime
        private set

    internal lateinit var escalationRuntime: PrivateEscalationRuntime
        private set

    private lateinit var goalDecisionTraceRecorder: GoalDecisionTraceRecorder
    private lateinit var liveSourceController: LiveSourceProcessController
    private lateinit var initialDataSources: AndroidInitialDataSourceCatalog
    private lateinit var lifePhotonRepository: CanonicalLifePhotonRepository
    private val selfHealingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val initialDataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val storageIntelligenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var storageIntelligenceJob: Job? = null
    private val selfObservationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var selfObservationJob: Job? = null
    private val selfObservationAnalysisMutex = Mutex()
    @Volatile
    private var lastSelfObservationWorldBand: SelfStateWorldBand? = null
    @Volatile
    private var lastSelfObservationTraceIdentity: String? = null
    private lateinit var selfObservationHealthGraph: app.lifeos.core.runtime.health.HealthGraph
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableStartupState = MutableStateFlow(LifeOsProcessStartupState.starting())
    private val mutableSelfObservationAnalysis =
        MutableStateFlow<SelfObservationAnalysisState?>(null)

    override val startupState: StateFlow<LifeOsProcessStartupState> = mutableStartupState.asStateFlow()
    val selfObservationAnalysis: StateFlow<SelfObservationAnalysisState?> =
        mutableSelfObservationAnalysis.asStateFlow()

    private val permissionController by lazy {
        PrivatePermissionController(
            application = this,
            initialDataSources = { initialDataSources },
            startupReady = { startupState.value.ready },
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Android must be allowed to render the launcher Activity immediately. The complete LIFEOS
        // stage DAG is still deterministic, but it no longer blocks the UI/main thread at process start.
        startupScope.launch {
            try {
                initializeRuntime()
                mutableStartupState.value = LifeOsProcessStartupState.ready()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableStartupState.value = LifeOsProcessStartupState.failed(
                    error.message ?: error::class.simpleName ?: "lifeos-startup-failed",
                )
            }
        }
    }

    private suspend fun initializeRuntime() {
        generatedToolStatusReader = GeneratedToolRuntimeStatusReader(
            EncryptedGeneratedToolStateRepository(this),
        )
        hardwareResourceIntelligence = HardwareResourceIntelligenceRuntime(this)
        storageIntelligence = AndroidStorageIntelligenceRuntime(
            context = this,
            hardware = hardwareResourceIntelligence,
        )
        storageMaintenance = AndroidStorageMaintenanceRuntime(
            context = this,
            hardware = hardwareResourceIntelligence,
        )
        LifeOsIntegratedCognitionSuiteRegistry.install(LifeOsIntegratedCognitionSuite())

        LifeOsStartupComposition.start(
            LifeOsStartupHooks(
                installSharedResourceRuntime = {
                    SharedResourceBudgetRuntimeRegistry.install(hardwareResourceIntelligence)
                    ownerPolicy = OwnerPolicyLedger(EncryptedOwnerPolicyRepository(this))
                    ExternalEffectRuntimeRegistry.install(
                        PolicyGatedExternalEffectExecutor(
                            policyGate = OwnerPolicyEffectGate(ownerPolicy),
                            receipts = EncryptedExternalEffectReceiptRepository(this),
                            payloads = EncryptedExternalPayloadRepository(this),
                            transport = ExternalTransportRuntimeRegistry.transport(),
                            observationReconciler = ExternalTransportRuntimeRegistry.reconciler(),
                        )
                    )
                    resourceBudgets = ResourceBudgetCoordinator(EncryptedResourceBudgetRepository(this))
                    decisionTraces = DecisionTraceLedger(EncryptedDecisionTraceRepository(this))
                    selfObservationDecisionTraceRecorder = SelfObservationDecisionTraceRecorder(decisionTraces)
                    goalDecisionTraceRecorder = GoalDecisionTraceRecorder(decisionTraces)
                    DecisionTraceRuntimeRegistry.install(SubsystemDecisionTraceRecorder(decisionTraces))
                    LifecycleDecisionTraceRuntimeRegistry.install(LifecycleDecisionTraceRecorder(decisionTraces))
                    PrivateOwnerPolicyBaseline.ensure(ownerPolicy)
                    WebDeepSearchRuntime.installPolicy(ownerPolicy)
                    GeneratedProviderRestoreAuthorityRuntimeRegistry.install(
                        GeneratedProviderRestoreAuthority(
                            ownerPolicy = ownerPolicy,
                            actorId = PrivateOwnerPolicyBaseline.ownerActorId,
                            scope = PrivateOwnerPolicyBaseline.GENERATED_PROVIDER_RESTORE_SCOPE,
                        )
                    )
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
                        context = this,
                        hardwareResourceIntelligence = hardwareResourceIntelligence,
                        bootReadyMaintenanceTrigger = {
                            refreshStorageIntelligence()
                        },
                    ).create()
                    val ownerAssetReviews = EncryptedOwnerAssetReviewRepository(this)
                    photonIngress = CanonicalPhotonIngress(kernel, ownerAssetReviews)
                    lifePhotonRepository = CanonicalLifePhotonRepository(
                        delegate = kernel.photonStore,
                        productiveIngress = photonIngress::ingest,
                    )
                    lifeMemoryRuntime = DurableLifeMemoryRuntime(lifePhotonRepository)
                    DurableLifeMemoryRuntimeRegistry.install(lifeMemoryRuntime)
                    multimodalPerception = MultimodalPerceptionRuntime(kernel)
                    multimodalPerception.install()
                    val integratedCognition = requireNotNull(LifeOsIntegratedCognitionSuiteRegistry.current()) {
                        "Integrated cognition suite must be installed before kernel composition"
                    }
                    val basePersistence = CausalDerivedPhotonPersistence { derived, _ ->
                        photonIngress.ingest(derived, PhotonIngressMode.DERIVED)
                    }
                    val domainPersistence = DomainEvidenceConvergingPersistence(
                        delegate = basePersistence,
                        convergence = DomainEvidenceConvergenceCoordinator(kernel.photonStore),
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
                        photonIngress.ingest(planned, PhotonIngressMode.DERIVED)
                    }
                    val frozenCognitiveModules =
                        kernel.freezeCognitiveModulesForCurrentCycle(
                            integratedCognition.domainModules
                        )
                    val causalCoordinator = RecursiveCausalCognitionCoordinator(
                        modules = frozenCognitiveModules,
                        engine = CausalCognitionEngine(
                            ledger = PhotonBackedCausalLedgerStore(kernel.photonStore),
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
                        photonIngress.ingest(photon, PhotonIngressMode.ORIGIN)
                        photon
                    }
                    NovelPromotionRuntimeEventRegistry.install { promotion ->
                        val capability = promotion.activeRecord.manifest.sourceCapability.value
                        val toolId = promotion.activeRecord.manifest.toolId
                        photonIngress.ingest(
                            Photon(
                                content = "Controlled Evolution aktiviert $capability über $toolId nach " +
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
                        requestSelfObservation(SelfObservationTrigger.TOOL_STATE_TRANSITION)
                        Unit
                    }
                },
                installDeepSearchRuntime = {
                    DeepSearchMissionRuntimeRegistry.install(
                        DeepSearchMissionCoordinator(
                            ledger = DeepSearchMissionLedger(EncryptedDeepSearchMissionRepository(this)),
                            checkpoints = DeepSearchCheckpointStore(EncryptedDeepSearchCheckpointRepository(this)),
                            resultPhotons = object : DeepSearchResultPhotonPersistence {
                                override suspend fun save(photon: Photon) {
                                    photonIngress.ingest(photon, PhotonIngressMode.DERIVED)
                                }

                                override suspend fun load(id: PhotonId): Photon? = kernel.photonStore.load(id)

                                override suspend fun findForMission(missionId: DeepSearchMissionId): Photon? {
                                    val tag = "deepsearch-mission:${missionId.value}"
                                    val matches = kernel.productivePhotonQueries.tags(
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
                    val healthGraph = requireNotNull(HealthGraphProcessRegistry.current()) {
                        "Kernel did not install its HealthGraph"
                    }
                    val quarantineRegistry = requireNotNull(QuarantineRegistryProcessRegistry.current()) {
                        "Kernel did not install its QuarantineRegistry"
                    }
                    val supervisor = requireNotNull(RuntimeSupervisorProcessRegistry.current()) {
                        "Kernel did not install its RuntimeSupervisor"
                    }
                    val protectionCoordinator = requireNotNull(
                        ProtectionCoordinatorProcessRegistry.current()
                    ) {
                        "Kernel did not install its ProtectionCoordinator"
                    }
                    selfHealingRuntime = PrivateSelfHealingRuntime.create(
                        context = this,
                        scope = selfHealingScope,
                        graph = healthGraph,
                        quarantineRegistry = quarantineRegistry,
                        budgets = resourceBudgets,
                        runtime = kernel.runtime,
                        supervisor = supervisor,
                    )
                    escalationRuntime = PrivateEscalationRuntime.create(
                        context = this,
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
                            photonIngress.ingest(photon, PhotonIngressMode.ORIGIN)
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
                                photonIngress.ingestWithReceipt(photon, PhotonIngressMode.DERIVED)
                            },
                            outcomeLookup = kernel.productivePhotonQueries,
                            cognitiveBindings = EncryptedGoalCognitiveCycleBindingRepository(this),
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
                    mutableStartupState.value = LifeOsProcessStartupState.starting(
                        stage = "BootEngine · ${evidence.stage.name.lowercase().replace('_', ' ')}",
                    )
                },
            )
        )

        selfObservationHealthGraph = requireNotNull(HealthGraphProcessRegistry.current()) {
            "Self observation requires the productive HealthGraph"
        }
        liveSourceController = LiveSourceProcessController(
            context = this,
            photonIngress = photonIngress,
            healthGraph = selfObservationHealthGraph,
            onSnapshot = { snapshot ->
                latestLiveSourceSync = snapshot
            },
            onFailure = { failure ->
                liveSourceSyncFailure = failure
            },
        )
        selfObservationRuntime = SelfObservationRuntime(
            photonIndex = kernel.photonStore::indexReport,
            memorySnapshot = lifeMemoryRuntime::current,
            authorityReader = SelfObservationAuthorityRuntimeRegistry.requireCurrent(),
            topologySnapshot = LifeOsProcessTopology::snapshot,
            healthSnapshot = { selfObservationHealthGraph.snapshot() },
            hardwareSnapshot = hardwareResourceIntelligence::currentHardwareSnapshot,
            toolStatus = generatedToolStatusReader::snapshot,
            activeRepairs = selfHealingRuntime.ledger::active,
            liveSourceSnapshot = { latestLiveSourceSync },
            liveSourceFailure = { liveSourceSyncFailure },
        )
        selfObservationCoordinator = SelfObservationCoordinator(
            capture = SelfObservationCapture {
                selfObservationRuntime.capture()
            }
        )
        startSelfObservation(selfObservationHealthGraph)
        liveSourceController.startContinuousRefresh()

        initialDataSources = AndroidInitialDataSourceCatalog(this)
        initialDataBootstrap = InitialDataBootstrapRuntime(
            photons = lifePhotonRepository,
            memory = lifeMemoryRuntime,
            sources = initialDataSources.sources + AndroidSharedFilesInitialDataSource(this),
        )
        refreshInitialDataBootstrap()
        refreshLiveSources()
    }

    private suspend fun startSelfObservation(
        healthGraph: app.lifeos.core.runtime.health.HealthGraph,
    ) {
        if (selfObservationJob?.isActive == true) return
        // Register before any event/UI-triggered refresh can emit the derived node with UNKNOWN scope.
        healthGraph.register(
            app.lifeos.core.runtime.health.HealthNodeId(SELF_OBSERVATION_HEALTH_NODE_ID),
            app.lifeos.core.runtime.health.HealthScope.RUNTIME,
        )
        selfObservationJob = selfObservationScope.launch {
            launch {
                healthGraph.observations.collect { observation ->
                    if (observation.source != SELF_OBSERVATION_HEALTH_SOURCE) {
                        val trigger = if (observation.state == app.lifeos.core.runtime.health.HealthState.RECOVERING) {
                            SelfObservationTrigger.RECOVERY_TRANSITION
                        } else {
                            SelfObservationTrigger.HEALTH_TRANSITION
                        }
                        refreshAndAnalyzeSelfObservation(trigger)
                    }
                }
            }
            var trigger = SelfObservationTrigger.STARTUP
            while (currentCoroutineContext().isActive) {
                val cycle = refreshAndAnalyzeSelfObservation(trigger)
                trigger = SelfObservationTrigger.TIMER
                delay(selfObservationCoordinator.nextInterval(cycle.band).toMillis())
            }
        }
    }

    fun refreshSelfObservation() {
        requestSelfObservation(SelfObservationTrigger.EXPLICIT_UI_REFRESH)
    }

    internal fun requestSelfObservation(trigger: SelfObservationTrigger) {
        if (!::selfObservationCoordinator.isInitialized) return
        selfObservationScope.launch {
            refreshAndAnalyzeSelfObservation(trigger)
        }
    }

    private suspend fun refreshAndAnalyzeSelfObservation(
        trigger: SelfObservationTrigger,
    ): app.lifeos.core.runtime.self.SelfObservationCycle {
        val cycle = selfObservationCoordinator.refresh(trigger)
        if (cycle.emitted) {
            analyzeSelfObservation(cycle)
        }
        return cycle
    }

    private suspend fun analyzeSelfObservation(
        cycle: app.lifeos.core.runtime.self.SelfObservationCycle,
    ) {
        selfObservationAnalysisMutex.withLock {
            val assessment = SelfStateWorldFormulaRuntimeRegistry.requireCurrent().evaluate(cycle.result)
            mutableSelfObservationAnalysis.value = SelfObservationAnalysisState(cycle, assessment)
            val traceIdentity = cycle.snapshot.authorityFingerprint + ":" + assessment.band.name
            if (traceIdentity != lastSelfObservationTraceIdentity) {
                selfObservationDecisionTraceRecorder.record(
                    snapshot = cycle.snapshot,
                    assessment = assessment,
                )
                lastSelfObservationTraceIdentity = traceIdentity
            }

            WorldEquationPostActivationSafetyRuntimeRegistry.current()?.observe(
                assessmentId = assessment.analysisId,
                authorityFingerprint = assessment.authorityFingerprint,
                band = assessment.band,
                observedAt = cycle.snapshot.capturedAt,
            )

            val previousBand = lastSelfObservationWorldBand
            if (assessment.band != previousBand) {
                when (assessment.band) {
                    SelfStateWorldBand.CRITICAL -> selfObservationHealthGraph.record(
                        app.lifeos.core.runtime.health.HealthObservation(
                            nodeId = app.lifeos.core.runtime.health.HealthNodeId(SELF_OBSERVATION_HEALTH_NODE_ID),
                            state = app.lifeos.core.runtime.health.HealthState.UNHEALTHY,
                            observedAt = cycle.snapshot.capturedAt,
                            source = SELF_OBSERVATION_HEALTH_SOURCE,
                            message = "self-observation:critical:" + assessment.reasonCodes.joinToString("|"),
                            actionable = false,
                        )
                    )
                    SelfStateWorldBand.DEGRADED -> selfObservationHealthGraph.record(
                        app.lifeos.core.runtime.health.HealthObservation(
                            nodeId = app.lifeos.core.runtime.health.HealthNodeId(SELF_OBSERVATION_HEALTH_NODE_ID),
                            state = app.lifeos.core.runtime.health.HealthState.DEGRADED,
                            observedAt = cycle.snapshot.capturedAt,
                            source = SELF_OBSERVATION_HEALTH_SOURCE,
                            message = "self-observation:degraded:" + assessment.reasonCodes.joinToString("|"),
                            actionable = false,
                        )
                    )
                    SelfStateWorldBand.STABLE,
                    SelfStateWorldBand.OBSERVE -> {
                        if (
                            previousBand == SelfStateWorldBand.DEGRADED ||
                            previousBand == SelfStateWorldBand.CRITICAL
                        ) {
                            selfObservationHealthGraph.recordHealthy(
                                id = app.lifeos.core.runtime.health.HealthNodeId(SELF_OBSERVATION_HEALTH_NODE_ID),
                                source = SELF_OBSERVATION_HEALTH_SOURCE,
                                message = "self-observation:recovered",
                                observedAt = cycle.snapshot.capturedAt,
                                actionable = false,
                            )
                        }
                    }
                }
                lastSelfObservationWorldBand = assessment.band
            }
        }
    }


    fun refreshStorageIntelligence() {
        if (!::storageIntelligence.isInitialized || !hasBroadFileAccess()) return
        if (storageIntelligenceJob?.isActive == true) return
        storageIntelligenceJob = storageIntelligenceScope.launch {
            try {
                while (currentCoroutineContext().isActive) {
                    val snapshot = storageIntelligence.runNextSlice()
                    latestStorageIntelligence = snapshot
                    storageIntelligenceFailure = null
                    if (snapshot.contentReadComplete) break

                    val hardware = hardwareResourceIntelligence.currentHardwareSnapshot()
                    val batteryFraction = hardware.batteryFraction
                    val delayMillis = when {
                        hardware.charging == true -> 1_000L
                        batteryFraction != null && batteryFraction < 0.20 -> 60_000L
                        else -> 15_000L
                    }
                    delay(delayMillis)
                }
            } catch (error: Exception) {
                storageIntelligenceFailure =
                    error.message ?: error::class.simpleName ?: "storage-intelligence-failed"
            }
        }
    }

    fun refreshLiveSources() {
        if (!::liveSourceController.isInitialized) return
        liveSourceController.refresh()
    }

    fun initialDataPermissionsToRequest(): List<String> =
        permissionController.initialDataPermissionsToRequest()

    fun allRuntimePermissionsToRequest(): List<String> =
        permissionController.allRuntimePermissionsToRequest()

    fun hasBroadFileAccess(): Boolean =
        permissionController.hasBroadFileAccess()

    fun shouldRequestBroadFileAccess(): Boolean =
        permissionController.shouldRequestBroadFileAccess()

    fun markBroadFileAccessRequested() =
        permissionController.markBroadFileAccessRequested()

    fun shouldRequestInitialDataPermissions(): Boolean =
        permissionController.shouldRequestInitialDataPermissions()

    fun shouldRequestAllRuntimePermissions(): Boolean =
        permissionController.shouldRequestAllRuntimePermissions()

    fun markInitialDataPermissionsRequested() =
        permissionController.markInitialDataPermissionsRequested()

    fun markAllRuntimePermissionsRequested() =
        permissionController.markAllRuntimePermissionsRequested()

    fun refreshInitialDataBootstrap() {
        if (!startupState.value.ready && !::initialDataBootstrap.isInitialized) return
        initialDataScope.launch {
            try {
                latestInitialDataBootstrap = initialDataBootstrap.run()
                initialDataBootstrapFailure = null
            } catch (error: Exception) {
                initialDataBootstrapFailure = error.message ?: error::class.simpleName ?: "initial-data-bootstrap-failed"
            }
        }
    }


}
