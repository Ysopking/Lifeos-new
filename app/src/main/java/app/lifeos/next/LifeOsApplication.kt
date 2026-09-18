package app.lifeos.next

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
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
import app.lifeos.core.runtime.StaticCognitiveModuleRegistry
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
import app.lifeos.next.kernel.PrivateFuturePlanningAuthority
import app.lifeos.next.kernel.PrivateGoalActionExecutionGuard
import app.lifeos.next.kernel.PrivateOwnerPolicyBaseline
import app.lifeos.next.kernel.PrivateSelfHealingRuntime
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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

    lateinit var ownerPolicy: OwnerPolicyLedger
        private set

    lateinit var resourceBudgets: ResourceBudgetCoordinator
        private set

    lateinit var decisionTraces: DecisionTraceLedger
        private set

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

    internal lateinit var selfHealingRuntime: PrivateSelfHealingRuntime
        private set

    private lateinit var goalDecisionTraceRecorder: GoalDecisionTraceRecorder
    private lateinit var initialDataSources: AndroidInitialDataSourceCatalog
    private lateinit var lifePhotonRepository: CanonicalLifePhotonRepository
    private val selfHealingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val initialDataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
                    val causalCoordinator = RecursiveCausalCognitionCoordinator(
                        modules = StaticCognitiveModuleRegistry(integratedCognition.domainModules),
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
                                    val matches = kernel.photonStore.loadAll().filter { tag in it.tags }
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
                    selfHealingRuntime = PrivateSelfHealingRuntime.create(
                        context = this,
                        scope = selfHealingScope,
                        graph = healthGraph,
                        quarantineRegistry = quarantineRegistry,
                        budgets = resourceBudgets,
                        runtime = kernel.runtime,
                        supervisor = supervisor,
                    )
                    runBlocking {
                        selfHealingRuntime.verifyLedgerIntegrity()
                    }
                    LifeOsHealthPhotonBridge.start(
                        scope = selfHealingScope,
                        graph = healthGraph,
                        persist = { photon ->
                            photonIngress.ingest(photon, PhotonIngressMode.ORIGIN)
                            Unit
                        },
                    )
                    selfHealingRuntime.orchestrator.start()
                },
                installDurableGoalPlanRuntime = {
                    val durableV5Decisions = DurableConvergenceDecisionCoordinator(
                        EncryptedConvergenceDecisionCheckpointRepository(this),
                    )
                    DurableGoalPlanRuntimeRegistry.install(
                        DurableGoalPlanRuntime(
                            ledger = kernel.goalPlans,
                            convergence = GoalConvergenceDecisionProvider(durableV5Decisions),
                            persistDerivedOutcome = { photon ->
                                photonIngress.ingestWithReceipt(photon, PhotonIngressMode.DERIVED)
                            },
                            loadPersistedPhotons = kernel.photonStore::loadAll,
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

        initialDataSources = AndroidInitialDataSourceCatalog(this)
        initialDataBootstrap = InitialDataBootstrapRuntime(
            photons = lifePhotonRepository,
            memory = lifeMemoryRuntime,
            sources = initialDataSources.sources + AndroidSharedFilesInitialDataSource(this),
        )
        refreshInitialDataBootstrap()
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
        const val INITIAL_DATA_PREFS = "lifeos-initial-data-bootstrap"
        const val INITIAL_DATA_PERMISSION_SCHEMA = "permission-schema"
        const val ALL_RUNTIME_PERMISSION_SCHEMA = "all-runtime-permission-schema"
        const val BROAD_FILE_ACCESS_REQUESTED = "broad-file-access-requested"
    }
}
