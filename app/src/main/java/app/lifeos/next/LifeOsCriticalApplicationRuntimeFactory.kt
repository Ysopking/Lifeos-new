package app.lifeos.next

import android.content.Context
import app.lifeos.core.data.LiveSourceSyncSnapshot
import app.lifeos.core.runtime.health.HealthGraphProcessRegistry
import app.lifeos.core.runtime.health.SelfHealingIncidentSnapshot
import app.lifeos.core.runtime.life.InitialDataBootstrapRuntime
import app.lifeos.core.runtime.life.InitialDataBootstrapSnapshot
import app.lifeos.core.runtime.reasoning.MetaRealizationShadowRuntime
import app.lifeos.core.runtime.reasoning.MetaRealizationShadowRuntimeRegistry
import app.lifeos.core.runtime.self.SelfObservationAuthorityRuntimeRegistry
import app.lifeos.core.runtime.self.SelfObservationCapture
import app.lifeos.core.runtime.self.SelfObservationCoordinator
import app.lifeos.core.runtime.topology.LifeOsProcessTopology
import app.lifeos.next.kernel.SelfObservationRuntime

internal data class LifeOsCriticalApplicationRuntime(
    val sharedFileEvidenceMigration: SharedFileEvidenceMigrationCoordinator,
    val liveSourceController: LiveSourceProcessController,
    val selfObservationRuntime: SelfObservationRuntime,
    val selfObservationCoordinator: SelfObservationCoordinator,
    val selfObservationController: SelfObservationProcessController,
    val metaRealizationShadowRuntime: MetaRealizationShadowRuntime,
    val initialDataSources: AndroidInitialDataSourceCatalog,
    val initialDataBootstrap: InitialDataBootstrapRuntime,
    val initialDataController: InitialDataProcessController,
)

internal object LifeOsCriticalApplicationRuntimeFactory {
    fun create(
        context: Context,
        installed: ProcessRuntimeCriticalInstallResult,
        startupActionable: () -> Boolean,
        activeRepairs: suspend () -> List<SelfHealingIncidentSnapshot>,
        latestLiveSourceSync: () -> LiveSourceSyncSnapshot?,
        liveSourceFailure: () -> String?,
        onLiveSourceSnapshot: (LiveSourceSyncSnapshot) -> Unit,
        onLiveSourceFailure: (String?) -> Unit,
        onSelfObservationAnalysis: (SelfObservationAnalysisState) -> Unit,
        onInitialDataSnapshot: (InitialDataBootstrapSnapshot) -> Unit,
        onInitialDataFailure: (String?) -> Unit,
    ): LifeOsCriticalApplicationRuntime {
        val migration = SharedFileEvidenceMigrationCoordinator(
            context,
            installed.lifeMemoryRuntime,
        )
        val healthGraph = requireNotNull(HealthGraphProcessRegistry.current()) {
            "Self observation requires the productive HealthGraph"
        }
        val liveSources = LiveSourceProcessController(
            context = context,
            photonIngress = installed.photonIngress,
            healthGraph = healthGraph,
            onSnapshot = { snapshot ->
                onLiveSourceSnapshot(snapshot)
                migration.onLiveSnapshot(snapshot)
            },
            onFailure = onLiveSourceFailure,
        )
        val metaRealizationShadowRuntime = MetaRealizationShadowRuntime()
        MetaRealizationShadowRuntimeRegistry.install(metaRealizationShadowRuntime)
        val selfObservation = SelfObservationRuntime(
            photonIndex = installed.kernel.photonStore::indexReport,
            memorySnapshot = installed.lifeMemoryRuntime::current,
            authorityReader = SelfObservationAuthorityRuntimeRegistry.requireCurrent(),
            topologySnapshot = LifeOsProcessTopology::snapshot,
            healthSnapshot = { healthGraph.snapshot() },
            hardwareSnapshot = installed.hardwareResourceIntelligence::currentHardwareSnapshot,
            toolStatus = installed.generatedToolStatusReader::snapshot,
            activeRepairs = activeRepairs,
            liveSourceSnapshot = latestLiveSourceSync,
            liveSourceFailure = liveSourceFailure,
        )
        val selfCoordinator = SelfObservationCoordinator(
            capture = SelfObservationCapture { selfObservation.capture() },
        )
        val selfController = SelfObservationProcessController(
            coordinator = selfCoordinator,
            healthGraph = healthGraph,
            traceRecorder = installed.selfObservationDecisionTraceRecorder,
            onAnalysis = onSelfObservationAnalysis,
        )
        val dataSources = AndroidInitialDataSourceCatalog(context)
        val dataBootstrap = InitialDataBootstrapRuntime(
            photons = installed.lifePhotonRepository,
            memory = installed.lifeMemoryRuntime,
            sources = dataSources.sources,
        )
        val dataController = InitialDataProcessController(
            bootstrap = { dataBootstrap },
            startupReady = startupActionable,
            onSnapshot = onInitialDataSnapshot,
            onFailure = onInitialDataFailure,
        )
        return LifeOsCriticalApplicationRuntime(
            sharedFileEvidenceMigration = migration,
            liveSourceController = liveSources,
            selfObservationRuntime = selfObservation,
            selfObservationCoordinator = selfCoordinator,
            selfObservationController = selfController,
            metaRealizationShadowRuntime = metaRealizationShadowRuntime,
            initialDataSources = dataSources,
            initialDataBootstrap = dataBootstrap,
            initialDataController = dataController,
        )
    }
}
