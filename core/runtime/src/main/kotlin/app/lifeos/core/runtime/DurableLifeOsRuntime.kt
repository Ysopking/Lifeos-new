package app.lifeos.core.runtime

import app.lifeos.core.model.Photon
import app.lifeos.core.runtime.tasks.DurableProcessingPipeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DurableLifeOsRuntime(
    private val scope: CoroutineScope,
    private val pipeline: DurableProcessingPipeline,
    private val stateBridge: DurableRuntimeStateBridge,
) : LifeOsRuntime {
    override val state: StateFlow<RuntimeState> = stateBridge.state

    private val lifecycleLock = Any()
    private var stopJob: Job? = null

    override fun start() = synchronized(lifecycleLock) {
        when (state.value.status) {
            RuntimeStatus.STARTING,
            RuntimeStatus.RUNNING,
            RuntimeStatus.STOPPING -> return@synchronized

            else -> Unit
        }

        if (stopJob?.isActive == true) return@synchronized
        stopJob = null
        stateBridge.markStarting()
        try {
            pipeline.start()
            stateBridge.markRunning()
        } catch (error: Exception) {
            stateBridge.markFailed(error)
            throw error
        }
    }

    override fun stop() = synchronized(lifecycleLock) {
        when (state.value.status) {
            RuntimeStatus.CREATED,
            RuntimeStatus.STOPPING,
            RuntimeStatus.STOPPED -> return@synchronized

            else -> Unit
        }

        stateBridge.markStopping()
        stopJob = scope.launch {
            try {
                pipeline.stop()
                stateBridge.markStopped()
            } catch (cancelled: CancellationException) {
                stateBridge.markStopped()
                throw cancelled
            } catch (error: Exception) {
                stateBridge.markFailed(error)
            }
        }
    }

    override suspend fun ingest(photon: Photon) {
        pipeline.submitPhoton(photon)
    }
}
