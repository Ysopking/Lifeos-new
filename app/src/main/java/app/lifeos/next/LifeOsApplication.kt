package app.lifeos.next

import android.app.Application
import app.lifeos.core.data.LiveSourceSyncSnapshot
import app.lifeos.core.runtime.boot.RuntimeAvailability
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
import app.lifeos.next.kernel.ProductiveAppUsageSensorRuntimeRegistry
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
    private lateinit var sharedFileEvidenceMigration: SharedFileEvidenceMigrationCoordinator
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
            startupReady = { startupState.value.actionable },
        )
    }

    override fun onCreate() {
        super.onCreate()
        startupScope.launch {
            try {
                initializeRuntime()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val current = mutableStartupState.value
                if (current.ready) {
                    mutableStartupState.value = current.copy(
                        availability = if (current.availability == RuntimeAvailability.FULL) {
                            RuntimeAvailability.DEGRADED
                        } else {
                            current.availability
                        },
                        stage = "Runtime eingeschränkt",
                        failure =
                            error.message ?: error::class.simpleName ?: "lifeos-warm-startup-failed",
                    )
                } else if (current.phase != LifeOsProcessStartupPhase.FAILED) {
                    mutableStartupState.value = LifeOsProcessStartupState.failed(
                        error.message ?: error::class.simpleName ?: "lifeos-startup-failed",
                    )
                }
            }
        }
    }

    private fun onStartupEvent(event: LifeOsStartupStageEvent) {
        val current = mutableStartupState.value
        if (
            current.ready &&
            LifeOsStartupStageGraph.laneFor(event.stage) == LifeOsStartupLane.WARM
        ) {
            if (event is LifeOsStartupStageEvent.Failed) {
                mutableStartupState.value = current.copy(
                    availability = if (current.availability == RuntimeAvailability.FULL) {
                        RuntimeAvailability.DEGRADED
                    } else {
                        current.availability
                    },
                    stage = "Runtime eingeschränkt",
                    diagnosticCode = event.diagnosticCode,
                    durationMillis = event.durationMillis,
                    failure = "Warm Boot · ${event.stage.displayName}: ${event.message}",
                )
            }
            return
        }

        mutableStartupState.value = when (event) {
            is LifeOsStartupStageEvent.Started ->
                LifeOsProcessStartupState.stageStarted(event.stage)
            is LifeOsStartupStageEvent.Completed ->
                LifeOsProcessStartupState.stageCompleted(event)
            is LifeOsStartupStageEvent.Failed ->
                LifeOsProcessStartupState.stageFailed(event)
        }
    }

    private suspend fun initializeRuntime() {
        val installed = ProcessRuntimeInstaller(
            context = this,
            onSelfObservationRequested = ::requestSelfObservation,
            canRunStorageIntelligence = { hasBroadFileAccess() },
            onStorageSnapshot = { snapshot ->
                latestStorageIntelligence = snapshot
                if (::sharedFileEvidenceMigration.isInitialized) {
                    sharedFileEvidenceMigration.onStorageSnapshot(snapshot)
                }
                if (::liveSourceController.isInitialized) {
                    liveSourceController.refresh()
                }
            },
            onStorageFailure = { failure ->
                storageIntelligenceFailure = failure
            },
            onStartupEvent = ::onStartupEvent,
            onCriticalReady = ::applyCriticalInstall,
        ).install()

        applyWarmInstall(installed.warm)
    }

    private fun applyCriticalInstall(installed: ProcessRuntimeCriticalInstallResult) {
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
        sharedFileEvidenceMigration =
            SharedFileEvidenceMigrationCoordinator(this, lifeMemoryRuntime)
        multimodalPerception = installed.multimodalPerception

        val selfObservationHealthGraph = requireNotNull(HealthGraphProcessRegistry.current()) {
            "Self observation requires the productive HealthGraph"
        }
        liveSourceController = LiveSourceProcessController(
            context = this,
            photonIngress = photonIngress,
            healthGraph = selfObservationHealthGraph,
            onSnapshot = { snapshot ->
                latestLiveSourceSync = snapshot
                sharedFileEvidenceMigration.onLiveSnapshot(snapshot)
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
            activeRepairs = {
                if (::selfHealingRuntime.isInitialized) {
                    selfHealingRuntime.ledger.active()
                } else {
                    emptyList()
                }
            },
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

        initialDataSources = AndroidInitialDataSourceCatalog(this)
        initialDataBootstrap = InitialDataBootstrapRuntime(
            photons = lifePhotonRepository,
            memory = lifeMemoryRuntime,
            sources = initialDataSources.sources,
        )
        initialDataController = InitialDataProcessController(
            bootstrap = { initialDataBootstrap },
            startupReady = { startupState.value.actionable },
            onSnapshot = { snapshot ->
                latestInitialDataBootstrap = snapshot
            },
            onFailure = { failure ->
                initialDataBootstrapFailure = failure
            },
        )

        val availability = kernel.bootstrapState.value.availability
        mutableStartupState.value = LifeOsProcessStartupState.ready(availability)

        if (startupState.value.actionable) {
            selfObservationController.start()
            liveSourceController.startContinuousRefresh()
            refreshInitialDataBootstrap()
            refreshLiveSources()
            refreshStorageIntelligence()
        }
    }

    private fun applyWarmInstall(installed: ProcessRuntimeWarmInstallResult) {
        installed.selfHealingRuntime?.let {
            selfHealingRuntime = it
        }
        installed.escalationRuntime?.let {
            escalationRuntime = it
        }

        val kernelAvailability = kernel.bootstrapState.value.availability
        val warmDegraded =
            installed.startupReport.degraded ||
                kernelAvailability == RuntimeAvailability.DEGRADED
        if (warmDegraded) {
            val current = mutableStartupState.value
            if (current.ready) {
                val summary = installed.startupReport.failures
                    .joinToString("; ") { "${it.diagnosticCode}:${it.stage.displayName}" }
                    .ifBlank { "kernel-warm-rehydration" }
                mutableStartupState.value = current.copy(
                    availability = if (current.availability == RuntimeAvailability.FULL) {
                        RuntimeAvailability.DEGRADED
                    } else {
                        current.availability
                    },
                    stage = "Runtime eingeschränkt",
                    failure = current.failure ?: "Warm Boot eingeschränkt: $summary",
                )
            }
        }

        if (startupState.value.actionable) {
            refreshSelfObservation()
        }
    }

    fun refreshSelfObservation() {
        if (!startupState.value.actionable) return
        if (!::selfObservationController.isInitialized) return
        selfObservationController.refresh()
    }

    internal fun requestSelfObservation(trigger: SelfObservationTrigger) {
        if (!startupState.value.actionable) return
        if (!::selfObservationController.isInitialized) return
        selfObservationController.request(trigger)
    }

    fun refreshStorageIntelligence() {
        if (!startupState.value.actionable) return
        if (!::storageIntelligenceController.isInitialized) return
        storageIntelligenceController.refresh()
    }

    fun refreshLiveSources() {
        if (!startupState.value.actionable) return
        if (::liveSourceController.isInitialized) {
            liveSourceController.refresh()
        }
        startupScope.launch {
            ProductiveAppUsageSensorRuntimeRegistry.refreshAvailability()
        }
    }

    internal fun runtimePermissionRequestPlan(): RuntimePermissionRequestPlan =
        permissionController.runtimePermissionRequestPlan()

    internal fun specialAccessRequestPlan(): SpecialAccessRequestPlan =
        permissionController.specialAccessRequestPlan()

    internal fun deviceAccessSnapshot(): DeviceAccessSnapshot =
        permissionController.deviceAccessSnapshot()

    fun hasBroadFileAccess(): Boolean =
        permissionController.hasBroadFileAccess()

    fun refreshInitialDataBootstrap() {
        if (!startupState.value.actionable) return
        if (!::initialDataController.isInitialized) return
        initialDataController.refresh()
    }
}
