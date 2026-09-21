package app.lifeos.next

import android.content.Context
import app.lifeos.core.data.EncryptedLiveSourceCursorRepository
import app.lifeos.core.data.EncryptedLiveSourceSnapshotRepository
import app.lifeos.core.data.HealthGraphLiveSourceHealthReporter
import app.lifeos.core.data.LiveDataHubAuthority
import app.lifeos.core.data.LiveSourceDeltaCoordinator
import app.lifeos.core.data.LiveSourceSyncSnapshot
import app.lifeos.core.runtime.health.HealthGraph
import app.lifeos.next.kernel.CanonicalPhotonIngress
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class LiveSourceProcessController(
    context: Context,
    photonIngress: CanonicalPhotonIngress,
    healthGraph: HealthGraph,
    private val onSnapshot: (LiveSourceSyncSnapshot) -> Unit,
    private val onFailure: (String?) -> Unit,
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val refreshInterval: Duration = Duration.ofMinutes(5),
) {
    private val appContext = context.applicationContext
    private val coordinator = LiveSourceDeltaCoordinator(
        connectors = AndroidLiveSourceConnectors.create(appContext),
        cursors = EncryptedLiveSourceCursorRepository(appContext),
        hub = LiveDataHubAuthority.from(photonIngress.liveData),
        snapshots = EncryptedLiveSourceSnapshotRepository(appContext),
        maxInventoryItems = 16_384,
        maxRawDeltas = 16_384,
        coalescedCapacity = 16_384,
        health = HealthGraphLiveSourceHealthReporter(healthGraph),
    )

    private val refreshSignals = Channel<Unit>(Channel.CONFLATED)
    private val refreshWorker = scope.launch {
        for (signal in refreshSignals) {
            syncOnce()
        }
    }
    private var refreshJob: Job? = null

    fun refresh() {
        check(refreshWorker.isActive) {
            "Live-source refresh worker is not active"
        }
        refreshSignals.trySend(Unit)
    }

    fun startContinuousRefresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            while (currentCoroutineContext().isActive) {
                delay(refreshInterval.toMillis())
                refreshSignals.send(Unit)
            }
        }
    }

    private suspend fun syncOnce() {
        try {
            onSnapshot(coordinator.syncAll())
            onFailure(null)
        } catch (error: Exception) {
            onFailure(
                error.message ?: error::class.simpleName ?: "live-source-sync-failed",
            )
        }
    }
}
