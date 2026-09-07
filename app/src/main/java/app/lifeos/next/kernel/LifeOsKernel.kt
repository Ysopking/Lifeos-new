package app.lifeos.next.kernel

import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.runtime.LifeOsRuntime
import app.lifeos.core.runtime.RuntimeSupervisor
import app.lifeos.core.runtime.ThoughtMatrix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Process-level owner for the current LIFEOS runtime graph.
 *
 * This class deliberately stays small: it owns lifecycle and exposes the
 * already-existing runtime, matrix and persistence ports. Android Application
 * wiring is introduced in a later tranche.
 */
class LifeOsKernel internal constructor(
    val runtime: LifeOsRuntime,
    val matrix: ThoughtMatrix,
    val photonStore: PhotonRepository,
    private val supervisor: RuntimeSupervisor,
    private val scope: CoroutineScope,
) {
    fun start(): Job = scope.launch {
        supervisor.start()
    }

    fun stop(): Job = scope.launch {
        supervisor.stop()
    }

    /** Final process teardown hook; normal Activity/ViewModel destruction must not call this. */
    internal fun shutdown() {
        runtime.stop()
        scope.cancel()
    }
}
