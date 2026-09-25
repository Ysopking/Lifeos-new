package app.lifeos.next

import android.app.Application
import app.lifeos.core.data.LiveSourceSyncSnapshot
import app.lifeos.core.runtime.boot.RuntimeAvailability
import app.lifeos.core.runtime.capability.GeneratedToolRuntimeStatusReader
import app.lifeos.core.runtime.life.DurableLifeMemoryRuntime
import app.lifeos.core.runtime.life.InitialDataBootstrapRuntime
import app.lifeos.core.runtime.life.InitialDataBootstrapSnapshot
import app.lifeos.core.runtime.policy.OwnerPolicyLedger
import app.lifeos.core.runtime.resource.ResourceBudgetCoordinator
import app.lifeos.core.runtime.self.SelfObservationCoordinator
import app.lifeos.core.runtime.self.SelfObservationDecisionTraceRecorder
import app.lifeos.core.runtime.self.SelfObservationTrigger
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
            startupReady = { startupState.value.ready },
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
                if (!mutableStartupState.value.uiReady) {
                    mutableStartupState.value = LifeOsProcessStartupState.failed(
                        error.message ?: error::class.simpleName ?: "lifeos-startup-failed",
                    )
                } else if (mutableStartupState.value.availability == RuntimeAvailability.FULL) {
                    mutableStartupState.value = LifeOsProcessStartupState.ready(
                        RuntimeAvailability.DEGRADED
                    ).copy(failure = error.message ?: error::class.simpleName)
                }
            }
        }
    }

    private fun onStartupEvent(event: LifeOsStartupStageEvent) {
        val current = mutableStartupState.value
        val lane = LifeOsStartupStageGraph.laneOf(event.stage)
        if (current.uiReady && lane == StartupLane.WARM) {
            if (
                event is LifeOsStartupStageEvent.Failed &&
                current.availability == RuntimeAvailability.FULL
            ) {
                mutableStartupState.value = LifeOsProcessStartupState.ready(
                    RuntimeAvailability.DEGRADED
                ).copy(failure = "${event.diagnosticCode}: ${event.message}")
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
                if (::sharedFileEvidenceMigration.isInitialized) sharedFileEvidenceMigration.onStorageSnapshot(snapshot)
                if (::liveSourceController.isInitialized) liveSourceController.refresh()
            },
            onStorageFailure = { failure ->
                storageIntelligenceFailure = failure
            },
            onCriticalReady = ::onCriticalRuntimeReady,
            onStartupEvent = ::onStartupEvent,
        ).install()

        installed.warm.selfHealingRuntime?.let { selfHealingRuntime = it }
        installed.warm.escalationRuntime?.let { escalationRuntime = it }
        if (
            installed.warm.failures.isNotEmpty() &&
            mutableStartupState.value.availability == RuntimeAvailability.FULL
        ) {
            mutableStartupState.value = LifeOsProcessStartupState.ready(
                RuntimeAvailability.DEGRADED
            ).copy(failure = installed.warm.failures.sorted().joinToString("; "))
        }
        if (mutableStartupState.value.ready) {
            refreshSelfObservation()
        }
    }

    private suspend fun onCriticalRuntimeReady(installed: ProcessRuntimeCriticalInstallResult) {
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

        val controllers = LifeOsCriticalRuntimeComposition.create(
            context = this,
            installed = installed,
            traceRecorder = selfObservationDecisionTraceRecorder,
            activeRepairs = {
                if (::selfHealingRuntime.isInitialized) selfHealingRuntime.ledger.active()
                else emptyList()
            },
            liveSourceSnapshot = { latestLiveSourceSync },
            liveSourceFailure = { liveSourceSyncFailure },
            onLiveSnapshot = { latestLiveSourceSync = it },
            onLiveFailure = { liveSourceSyncFailure = it },
            onAnalysis = { mutableSelfObservationAnalysis.value = it },
            onInitialDataSnapshot = { latestInitialDataBootstrap = it },
            onInitialDataFailure = { initialDataBootstrapFailure = it },
            startupReady = { startupState.value.ready },
        )
        sharedFileEvidenceMigration = controllers.sharedFileEvidenceMigration
        liveSourceController = controllers.liveSourceController
        selfObservationRuntime = controllers.selfObservationRuntime
        selfObservationCoordinator = controllers.selfObservationCoordinator
        selfObservationController = controllers.selfObservationController
        initialDataSources = controllers.initialDataSources
        initialDataBootstrap = controllers.initialDataBootstrap
        initialDataController = controllers.initialDataController

        mutableStartupState.value = when (kernel.bootstrapState.value.availability) {
            RuntimeAvailability.FULL ->
                LifeOsProcessStartupState.ready(RuntimeAvailability.FULL)
            RuntimeAvailability.DEGRADED ->
                LifeOsProcessStartupState.ready(RuntimeAvailability.DEGRADED)
            RuntimeAvailability.READ_ONLY ->
                LifeOsProcessStartupState.readOnly(
                    kernel.bootstrapState.value.failureMessage ?: "Recovery is required"
                )
            RuntimeAvailability.RECOVERY ->
                LifeOsProcessStartupState.starting("Recovery wird vorbereitet")
            RuntimeAvailability.SAFE_MODE ->
                LifeOsProcessStartupState.restricted(
                    RuntimeAvailability.SAFE_MODE,
                    kernel.bootstrapState.value.failureMessage ?: "Sicherer Modus aktiv",
                )
        }

        if (mutableStartupState.value.ready) {
            selfObservationController.start()
            liveSourceController.startContinuousRefresh()
            refreshInitialDataBootstrap()
            refreshLiveSources()
            refreshStorageIntelligence()
        }
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
        if (!::initialDataController.isInitialized) return
        initialDataController.refresh()
    }
}
