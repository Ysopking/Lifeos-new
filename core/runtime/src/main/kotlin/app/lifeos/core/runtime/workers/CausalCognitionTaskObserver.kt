package app.lifeos.core.runtime.workers

import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.runtime.RecursiveCausalCognitionCoordinator
import app.lifeos.core.runtime.RuntimeFailure
import app.lifeos.core.runtime.RuntimeFailureCategory

/**
 * Productive bridge: only successfully completed durable Photon work may seed the recursive causal
 * runtime. The input Photon is reloaded from the authoritative repository at its completed revision.
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

        val summary = cognition.processRoot(photon)
        summary.failures.forEach { failureSink(it) }
    }
}
