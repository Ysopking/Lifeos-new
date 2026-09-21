package app.lifeos.next

import app.lifeos.core.runtime.life.InitialDataBootstrapRuntime
import app.lifeos.core.runtime.life.InitialDataBootstrapSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class InitialDataProcessController(
    private val bootstrap: () -> InitialDataBootstrapRuntime,
    private val startupReady: () -> Boolean,
    private val onSnapshot: (InitialDataBootstrapSnapshot) -> Unit,
    private val onFailure: (String?) -> Unit,
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val refreshSignals = Channel<Unit>(Channel.CONFLATED)
    private val refreshWorker = scope.launch {
        for (signal in refreshSignals) {
            if (!startupReady()) continue
            runRefreshCycle()
        }
    }

    fun refresh() {
        if (!startupReady()) return
        check(refreshWorker.isActive) {
            "Initial-data refresh worker is not active"
        }
        refreshSignals.trySend(Unit)
    }

    private suspend fun runRefreshCycle() {
        try {
            while (currentCoroutineContext().isActive) {
                val snapshot = bootstrap().run()
                onSnapshot(snapshot)
                onFailure(null)

                if (!snapshot.continuationRequired) {
                    break
                }

                delay(SLICE_DELAY_MILLIS)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onFailure(
                error.message ?: error::class.simpleName
                ?: "initial-data-bootstrap-failed",
            )
        }
    }

    private companion object {
        const val SLICE_DELAY_MILLIS = 100L
    }
}
