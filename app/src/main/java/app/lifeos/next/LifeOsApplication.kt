package app.lifeos.next

import android.app.Application
import app.lifeos.core.data.LiveSourceSyncSnapshot
import app.lifeos.core.runtime.capability.GeneratedToolRuntimeStatusReader
import app.lifeos.core.runtime.health.HealthGraphProcessRegistry
import app.lifeos.core.runtime.life.DurableLifeMemoryRuntime
import app.lifeos.core.runtime.life.InitialDataBootstrapRuntime
import app.lifeos.core.runtime.life.InitialDataBootstrapSnapshot
import app.lifeos.core.runtime.policy.OwnerPolicyLedger
import app.lifeos.core.runtime.resource.ResourceBudgetCoordinator
import app.lifeos.core.runtime.self.SelfObservationAuthorityRuntimeRegistry
import app.lifeos.core.runtime.self.SelfObservationCapture
import app.lifeos.core.runtime.self.SelfObservationCoordinator
import app.lifeos.core.runtime.self.SelfObservationDecisionTraceRecorder
import app.lifeos.core.runtime.self.SelfObservationTrigger
import app.lifeos.core.runtime.topology.LifeOsProcessTopology
import app.lifeos.core.runtime.trace.DecisionTraceLedger
import app.lifeos.core.runtime.trace.GoalDecisionTraceRecorder
import app.lifeos.next.kernel.CanonicalLifePhotonRepository
import app.lifeos.next.kernel.CanonicalPhotonIngress
import app.lifeos.next.kernel.HardwareResourceIntelligenceRuntime
import app.lifeos.next.kernel.LifeOsKernel
import app.lifeos.next.kernel.MultimodalPerceptionRuntime
import app.lifeos.next.kernel.PrivateEscalationRuntime
import app.lifeos.next.kernel.PrivateSelfHealingRuntime
import app.lifeos.next.kernel.SelfObservationRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

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
    private lateinit var selfObservationController: SelfObservationProcessController

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
    private lateinit var initialDataController: InitialDataProcessController
    private lateinit var storageIntelligenceController: StorageIntelligenceProcessController
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
        val installed = ProcessRuntimeInstaller(
            context = this,
            onSelfObservationRequested = ::requestSelfObservation,
            canRunStorageIntelligence = { hasBroadFileAccess() },
            onStorageSnapshot = { snapshot ->
                latestStorageIntelligence = snapshot
            },
            onStorageFailure = { failure ->
                storageIntelligenceFailure = failure
            },
            onStageReady = { evidence ->
                mutableStartupState.value = LifeOsProcessStartupState.starting(
                    stage =
                        "BootEngine · " +
                            evidence.stage.name.lowercase().replace('_', ' '),
                )
            },
        ).install()

        kernel = installed.kernel
        photonIngress = installed.photonIngress
        generatedToolStatusReader = installed.generatedToolStatusReader
        hardwareResourceIntelligence = installed.hardwareResourceIntelligence
        storageIntelligence = installed.storageIntelligence
        storageMaintenance = installed.storageMaintenance
        storageIntelligenceController = installed.storageIntelligenceController
        ownerPolicy = installed.ownerPolicy
        resourceBudgets = installed.resourceBudgets
        decisionTraces = installed.decisionTraces
        selfObservationDecisionTraceRecorder =
            installed.selfObservationDecisionTraceRecorder
        goalDecisionTraceRecorder = installed.goalDecisionTraceRecorder
        lifePhotonRepository = installed.lifePhotonRepository
        lifeMemoryRuntime = installed.lifeMemoryRuntime
        multimodalPerception = installed.multimodalPerception
        selfHealingRuntime = installed.selfHealingRuntime
        escalationRuntime = installed.escalationRuntime

        val selfObservationHealthGraph = requireNotNull(HealthGraphProcessRegistry.current()) {
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
        selfObservationController = SelfObservationProcessController(
            coordinator = selfObservationCoordinator,
            healthGraph = selfObservationHealthGraph,
            traceRecorder = selfObservationDecisionTraceRecorder,
            onAnalysis = { analysis ->
                mutableSelfObservationAnalysis.value = analysis
            },
        )
        selfObservationController.start()
        liveSourceController.startContinuousRefresh()

        initialDataSources = AndroidInitialDataSourceCatalog(this)
        initialDataBootstrap = InitialDataBootstrapRuntime(
            photons = lifePhotonRepository,
            memory = lifeMemoryRuntime,
            sources = initialDataSources.sources + AndroidSharedFilesInitialDataSource(this),
        )
        initialDataController = InitialDataProcessController(
            bootstrap = { initialDataBootstrap },
            startupReady = { startupState.value.ready },
            onSnapshot = { snapshot ->
                latestInitialDataBootstrap = snapshot
            },
            onFailure = { failure ->
                initialDataBootstrapFailure = failure
            },
        )
        refreshInitialDataBootstrap()
        refreshLiveSources()
    }

    fun refreshSelfObservation() {
        if (!::selfObservationController.isInitialized) return
        selfObservationController.refresh()
    }

    internal fun requestSelfObservation(trigger: SelfObservationTrigger) {
        if (!::selfObservationController.isInitialized) return
        selfObservationController.request(trigger)
    }

    fun refreshStorageIntelligence() {
        if (!::storageIntelligenceController.isInitialized) return
        storageIntelligenceController.refresh()
    }

    fun refreshLiveSources() {
        if (!::liveSourceController.isInitialized) return
        liveSourceController.refresh()
    }

    fun initialDataPermissionsToRequest(): List<String> = permissionController.initialDataPermissionsToRequest()

    fun allRuntimePermissionsToRequest(): List<String> = permissionController.allRuntimePermissionsToRequest()

    fun hasBroadFileAccess(): Boolean = permissionController.hasBroadFileAccess()

    fun shouldRequestBroadFileAccess(): Boolean = permissionController.shouldRequestBroadFileAccess()

    fun markBroadFileAccessRequested() = permissionController.markBroadFileAccessRequested()

    fun shouldRequestInitialDataPermissions(): Boolean = permissionController.shouldRequestInitialDataPermissions()

    fun shouldRequestAllRuntimePermissions(): Boolean = permissionController.shouldRequestAllRuntimePermissions()

    fun markInitialDataPermissionsRequested() = permissionController.markInitialDataPermissionsRequested()

    fun markAllRuntimePermissionsRequested() = permissionController.markAllRuntimePermissionsRequested()

    fun refreshInitialDataBootstrap() {
        if (!::initialDataController.isInitialized) return
        initialDataController.refresh()
    }

}
