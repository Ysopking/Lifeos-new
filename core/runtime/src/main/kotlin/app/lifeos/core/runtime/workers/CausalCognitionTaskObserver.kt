package app.lifeos.core.runtime.workers

import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.runtime.PhotonIngressMarkerStore
import app.lifeos.core.runtime.PhotonIngressMode
import app.lifeos.core.runtime.RecursiveCausalCognitionCoordinator
import app.lifeos.core.runtime.RuntimeFailure
import app.lifeos.core.runtime.RuntimeFailureCategory

/**
 * Productive bridge: only successfully completed durable ORIGIN Photon work may seed the recursive
 * causal runtime. DERIVED and REPLAY revisions still complete their normal durable field/live work,
 * but their persistent ingress marker prevents a second causal root pass after process restart too.
 */
class CausalCognitionTaskObserver(
    private val photons: PhotonRepository,
    private val cognition: RecursiveCausalCognitionCoordinator,
    private val failureSink: suspend (RuntimeFailure) -> Unit = {},
) : DurableTaskExecutionObserver {
    override suspend fun onExecutionResult(result: CognitiveTaskExecutionResult) {
        if (result.finalState != TaskState.COMPLETED) return
        val photonId = result.photonId ?: return
        val photon = try {
            photons.load(photonId)
        } catch (error: Exception) {
            failureSink(
                RuntimeFailure(
                    category = RuntimeFailureCategory.STORAGE,
                    source = "causal-cognition-task-observer",
                    message = error.message ?: "Photon reload failed",
                    photonId = photonId,
                )
            )
            return
        } ?: return

        val ingressMode = try {
            PhotonIngressMarkerStore.mode(photons, photon)
        } catch (error: Exception) {
            failureSink(
                RuntimeFailure(
                    category = RuntimeFailureCategory.INVARIANT,
                    source = "causal-cognition-task-observer",
                    message = error.message ?: "Photon ingress classification failed",
                    photonId = photonId,
                )
            )
            return
        }
        if (ingressMode != PhotonIngressMode.ORIGIN) return

        val summary = cognition.processRoot(photon)
        summary.failures.forEach { failureSink(it) }
    }
}
