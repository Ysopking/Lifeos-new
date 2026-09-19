package app.lifeos.next

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import app.lifeos.core.data.EncryptedLiveSourceCursorRepository
import app.lifeos.core.data.EncryptedLiveSourceSnapshotRepository
import app.lifeos.core.data.goal.EncryptedGoalCognitiveCycleBindingRepository
import app.lifeos.core.data.HealthGraphLiveSourceHealthReporter
import app.lifeos.core.data.LiveDataHubAuthority
import app.lifeos.core.data.LiveSourceDeltaCoordinator
import app.lifeos.core.data.LiveSourceSyncSnapshot
import app.lifeos.core.data.artifact.EncryptedOwnerAssetReviewRepository
import app.lifeos.core.data.capability.EncryptedGeneratedToolStateRepository
import app.lifeos.core.runtime.policy.OwnerPolicyEffectGate
import app.lifeos.core.runtime.agency.PolicyGatedExternalEffectExecutor
import app.lifeos.core.runtime.agency.ExternalTransportRuntimeRegistry
import app.lifeos.core.runtime.agency.ExternalEffectRuntimeRegistry
import app.lifeos.core.data.agency.EncryptedExternalEffectReceiptRepository
import app.lifeos.core.data.agency.EncryptedExternalPayloadRepository
import app.lifeos.core.data.convergence.EncryptedConvergenceDecisionCheckpointRepository
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
import app.lifeos.core.runtime.convergence.DurableConvergenceDecisionCoordinator
import app.lifeos.core.runtime.deepsearch.DeepSearchCheckpointStore
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionCoordinator
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionId
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionLedger
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionRuntimeRegistry
import app.lifeos.core.runtime.deepsearch.DeepSearchResultPhotonPersistence
import app.lifeos.core.runtime.evolution.NovelPromotionRuntimeEventRegistry
import app.lifeos.core.runtime.goal.GoalConvergenceDecisionProvider
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
import app.lifeos.core.runtime.self.SELF_OBSERVATION_HEALTH_NODE_ID
import app.lifeos.core.runtime.self.SELF_OBSERVATION_HEALTH_SOURCE
import app.lifeos.core.runtime.self.SelfObservationDecisionTraceRecorder
import app.lifeos.core.runtime.self.SelfObservationTrigger
import app.lifeos.core.runtime.topology.LifeOsProcessTopology
import app.lifeos.core.runtime.world.SelfStateWorldBand
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
import java.time.Duration
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    internal lateinit var selfHealingRuntime: PrivateSelfHealingRuntime
        private set

    internal lateinit var escalationRuntime: PrivateEscalationRuntime
        private set

    private lateinit var goalDecisionTraceRecorder: GoalDecisionTraceRecorder
    private lateinit var liveSourceCoordinator: LiveSourceDeltaCoordinator
    private lateinit var initialDataSources: AndroidInitialDataSourceCatalog
    private lateinit var lifePhotonRepository: CanonicalLifePhotonRepository
    private val selfHealingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val initialDataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val liveSourceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var liveSourceRefreshJob: Job? = null
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

    override val startupState: StateFlow<LifeOsProcessStartupState> = mutableStartupState.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        // Android must be allowed to render the launcher Activity immediately. The complete LIFEOS
        // stage DAG is still deterministic, but it no longer blocks the UI/main thread at process start.
        startupScope.launch {
            try {
                initializeRuntime()
                mutableStartupState.value = LifeOsProcessStartupState.ready()
            } catch (error: Throwable) {
                mutableStartupState.value = LifeOsProcessStartupState.failed(
                    error.message ?: error::class.simpleName ?: "lifeos-startup-failed",
                )
            }
        }
    }

    private fun initializeRuntime() {
        generatedToolStatusReader = GeneratedToolRuntimeStatusReader(
            EncryptedGeneratedToolStateRepository(this),
        )
        hardwareResourceIntelligence = HardwareResourceIntelligenceRuntime(this)
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
                    runBlocking {
                        PrivateOwnerPolicyBaseline.ensure(ownerPolicy)
                    }
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
                    runBlocking { multimodalPerception.install() }
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
                    runBlocking {
                        lifePhotonRepository.reconcilePersisted()
                        lifeMemoryRuntime.rebuild(Instant.now())
                        CognitiveSnapshotRuntimeRegistry.captureLatest()
                        futurePlanning.reconsiderAll().forEach { planned ->
                            photonIngress.ingest(planned, PhotonIngressMode.DERIVED)
                        }
                    }
                    val frozenCognitiveModules = runBlocking {
                        kernel.freezeCognitiveModulesForCurrentCycle(
                            integratedCognition.domainModules
                        )
                    }
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
                    runBlocking {
                        selfHealingRuntime.verifyLedgerIntegrity()
                        escalationRuntime.verifyLedgerIntegrity()
                    }
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

        installLiveSources()
        selfObservationHealthGraph = requireNotNull(HealthGraphProcessRegistry.current()) {
            "Self observation requires the productive HealthGraph"
        }
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
        startContinuousLiveSourceRefresh()

        initialDataSources = AndroidInitialDataSourceCatalog(this)
        initialDataBootstrap = InitialDataBootstrapRuntime(
            photons = lifePhotonRepository,
            memory = lifeMemoryRuntime,
            sources = initialDataSources.sources + AndroidSharedFilesInitialDataSource(this),
        )
        refreshInitialDataBootstrap()
        refreshLiveSources()
    }

    private fun startSelfObservation(
        healthGraph: app.lifeos.core.runtime.health.HealthGraph,
    ) {
        if (selfObservationJob?.isActive == true) return
        // Register the derived self-observation node before exposing any refresh path. Otherwise
        // an early health event/UI refresh can record it implicitly as UNKNOWN and a later explicit
        // RUNTIME registration fails closed on the scope mismatch.
        runBlocking {
            healthGraph.register(
                app.lifeos.core.runtime.health.HealthNodeId(SELF_OBSERVATION_HEALTH_NODE_ID),
                app.lifeos.core.runtime.health.HealthScope.RUNTIME,
            )
        }
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
            val traceIdentity = cycle.snapshot.authorityFingerprint + ":" + assessment.band.name
            if (traceIdentity != lastSelfObservationTraceIdentity) {
                selfObservationDecisionTraceRecorder.record(
                    snapshot = cycle.snapshot,
                    assessment = assessment,
                )
                lastSelfObservationTraceIdentity = traceIdentity
            }

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

    private fun installLiveSources() {
        val healthGraph = requireNotNull(HealthGraphProcessRegistry.current()) {
            "Live sources require the productive HealthGraph"
        }
        liveSourceCoordinator = LiveSourceDeltaCoordinator(
            connectors = AndroidLiveSourceConnectors.create(this),
            cursors = EncryptedLiveSourceCursorRepository(this),
            hub = LiveDataHubAuthority.from(photonIngress.liveData),
            snapshots = EncryptedLiveSourceSnapshotRepository(this),
            maxInventoryItems = 16_384,
            maxRawDeltas = 16_384,
            coalescedCapacity = 16_384,
            health = HealthGraphLiveSourceHealthReporter(healthGraph),
        )
    }

    fun refreshLiveSources() {
        if (!::liveSourceCoordinator.isInitialized) return
        liveSourceScope.launch {
            syncLiveSourcesOnce()
        }
    }

    private fun startContinuousLiveSourceRefresh() {
        if (liveSourceRefreshJob?.isActive == true) return
        liveSourceRefreshJob = liveSourceScope.launch {
            while (currentCoroutineContext().isActive) {
                delay(LIVE_SOURCE_REFRESH_INTERVAL.toMillis())
                syncLiveSourcesOnce()
            }
        }
    }

    private suspend fun syncLiveSourcesOnce() {
        if (!::liveSourceCoordinator.isInitialized) return
        try {
            latestLiveSourceSync = liveSourceCoordinator.syncAll()
            liveSourceSyncFailure = null
        } catch (error: Exception) {
            liveSourceSyncFailure =
                error.message ?: error::class.simpleName ?: "live-source-sync-failed"
        }
    }

    fun initialDataPermissionsToRequest(): List<String> = initialDataSources.missingRuntimePermissions()

    fun allRuntimePermissionsToRequest(): List<String> {
        if (!startupState.value.ready) return emptyList()
        return buildList {
            addAll(initialDataSources.missingRuntimePermissions())
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }.distinct().sorted()
    }

    fun hasBroadFileAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    fun shouldRequestBroadFileAccess(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || hasBroadFileAccess()) return false
        return !getSharedPreferences(INITIAL_DATA_PREFS, MODE_PRIVATE)
            .getBoolean(BROAD_FILE_ACCESS_REQUESTED, false)
    }

    fun markBroadFileAccessRequested() {
        getSharedPreferences(INITIAL_DATA_PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(BROAD_FILE_ACCESS_REQUESTED, true)
            .apply()
    }

    fun shouldRequestInitialDataPermissions(): Boolean {
        if (initialDataSources.missingRuntimePermissions().isEmpty()) return false
        val schema = initialDataSources.permissionSchemaFingerprint()
        return getSharedPreferences(INITIAL_DATA_PREFS, MODE_PRIVATE)
            .getString(INITIAL_DATA_PERMISSION_SCHEMA, null) != schema
    }

    fun shouldRequestAllRuntimePermissions(): Boolean {
        if (allRuntimePermissionsToRequest().isEmpty()) return false
        return getSharedPreferences(INITIAL_DATA_PREFS, MODE_PRIVATE)
            .getString(ALL_RUNTIME_PERMISSION_SCHEMA, null) != allRuntimePermissionSchema()
    }

    fun markInitialDataPermissionsRequested() {
        getSharedPreferences(INITIAL_DATA_PREFS, MODE_PRIVATE)
            .edit()
            .putString(INITIAL_DATA_PERMISSION_SCHEMA, initialDataSources.permissionSchemaFingerprint())
            .apply()
    }

    fun markAllRuntimePermissionsRequested() {
        getSharedPreferences(INITIAL_DATA_PREFS, MODE_PRIVATE)
            .edit()
            .putString(ALL_RUNTIME_PERMISSION_SCHEMA, allRuntimePermissionSchema())
            .apply()
    }

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

    private fun allRuntimePermissionSchema(): String = buildString {
        append(initialDataSources.permissionSchemaFingerprint())
        append("|record-audio")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) append("|post-notifications")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) append("|read-external-storage")
    }

    private companion object {
        val LIVE_SOURCE_REFRESH_INTERVAL: Duration = Duration.ofMinutes(5)
        const val INITIAL_DATA_PREFS = "lifeos-initial-data-bootstrap"
        const val INITIAL_DATA_PERMISSION_SCHEMA = "permission-schema"
        const val ALL_RUNTIME_PERMISSION_SCHEMA = "all-runtime-permission-schema"
        const val BROAD_FILE_ACCESS_REQUESTED = "broad-file-access-requested"
    }
}
