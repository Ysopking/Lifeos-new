package app.lifeos.next

import android.content.Context
import app.lifeos.core.data.LiveSourceSyncSnapshot
import app.lifeos.core.runtime.health.HealthGraphProcessRegistry
import app.lifeos.core.runtime.health.SelfHealingIncidentSnapshot
import app.lifeos.core.runtime.life.InitialDataBootstrapRuntime
import app.lifeos.core.runtime.life.InitialDataBootstrapSnapshot
import app.lifeos.core.runtime.self.SelfObservationAuthorityRuntimeRegistry
import app.lifeos.core.runtime.self.SelfObservationCapture
import app.lifeos.core.runtime.self.SelfObservationCoordinator
import app.lifeos.core.runtime.self.SelfObservationDecisionTraceRecorder
import app.lifeos.core.runtime.topology.LifeOsProcessTopology
import app.lifeos.next.kernel.SelfObservationRuntime

internal data class LifeOsCriticalRuntimeControllers(
    val sharedFileEvidenceMigration: SharedFileEvidenceMigrationCoordinator,
    val liveSourceController: LiveSourceProcessController,
    val selfObservationRuntime: SelfObservationRuntime,
    val selfObservationCoordinator: SelfObservationCoordinator,
    val selfObservationController: SelfObservationProcessController,
    val initialDataSources: AndroidInitialDataSourceCatalog,
    val initialDataBootstrap: InitialDataBootstrapRuntime,
    val initialDataController: InitialDataProcessController,
)

internal object LifeOsCriticalRuntimeComposition {
    fun create(
        context: Context,
        installed: ProcessRuntimeCriticalInstallResult,
        traceRecorder: SelfObservationDecisionTraceRecorder,
        activeRepairs: suspend () -> List<SelfHealingIncidentSnapshot>,
        liveSourceSnapshot: () -> LiveSourceSyncSnapshot?,
        liveSourceFailure: () -> String?,
        onLiveSnapshot: (LiveSourceSyncSnapshot) -> Unit,
        onLiveFailure: (String?) -> Unit,
        onAnalysis: (SelfObservationAnalysisState) -> Unit,
        onInitialDataSnapshot: (InitialDataBootstrapSnapshot) -> Unit,
        onInitialDataFailure: (String?) -> Unit,
        startupReady: () -> Boolean,
    ): LifeOsCriticalRuntimeControllers {
        val healthGraph = requireNotNull(HealthGraphProcessRegistry.current()) {
            "Self observation requires the productive HealthGraph"
        }
        val migration = SharedFileEvidenceMigrationCoordinator(
            context,
            installed.lifeMemoryRuntime,
        )
        val liveSources = LiveSourceProcessController(
            context = context,
            photonIngress = installed.photonIngress,
            healthGraph = healthGraph,
            onSnapshot = { snapshot ->
                onLiveSnapshot(snapshot)
                migration.onLiveSnapshot(snapshot)
            },
            onFailure = onLiveFailure,
        )
        val selfObservation = SelfObservationRuntime(
            photonIndex = installed.kernel.photonStore::indexReport,
            memorySnapshot = installed.lifeMemoryRuntime::current,
            authorityReader = SelfObservationAuthorityRuntimeRegistry.requireCurrent(),
            topologySnapshot = LifeOsProcessTopology::snapshot,
            healthSnapshot = { healthGraph.snapshot() },
            hardwareSnapshot = installed.hardwareResourceIntelligence::currentHardwareSnapshot,
            toolStatus = installed.generatedToolStatusReader::snapshot,
            activeRepairs = activeRepairs,
            liveSourceSnapshot = liveSourceSnapshot,
            liveSourceFailure = liveSourceFailure,
        )
        val coordinator = SelfObservationCoordinator(
            capture = SelfObservationCapture { selfObservation.capture() }
        )
        val controller = SelfObservationProcessController(
            coordinator = coordinator,
            healthGraph = healthGraph,
            traceRecorder = traceRecorder,
            onAnalysis = onAnalysis,
        )
        val sources = AndroidInitialDataSourceCatalog(context)
        val bootstrap = InitialDataBootstrapRuntime(
            photons = installed.lifePhotonRepository,
            memory = installed.lifeMemoryRuntime,
            sources = sources.sources,
        )
        val initialData = InitialDataProcessController(
            bootstrap = { bootstrap },
            startupReady = startupReady,
            onSnapshot = onInitialDataSnapshot,
            onFailure = onInitialDataFailure,
        )
        return LifeOsCriticalRuntimeControllers(
            sharedFileEvidenceMigration = migration,
            liveSourceController = liveSources,
            selfObservationRuntime = selfObservation,
            selfObservationCoordinator = coordinator,
            selfObservationController = controller,
            initialDataSources = sources,
            initialDataBootstrap = bootstrap,
            initialDataController = initialData,
        )
    }
}
