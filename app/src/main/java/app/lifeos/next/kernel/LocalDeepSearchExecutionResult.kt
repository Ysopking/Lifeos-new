package app.lifeos.next.kernel

import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.deepsearch.DeepSearchStatus

sealed interface LocalDeepSearchExecutionResult {
    data class Produced(
        val status: DeepSearchStatus,
        val output: PhotonSubmissionResult,
        val evidencePhotonIds: List<PhotonId>,
        val workUnitsUsed: Int,
    ) : LocalDeepSearchExecutionResult {
        init { require(workUnitsUsed >= 0) }
    }

    data class Failed(val message: String) : LocalDeepSearchExecutionResult {
        init { require(message.isNotBlank()) }
    }
}
