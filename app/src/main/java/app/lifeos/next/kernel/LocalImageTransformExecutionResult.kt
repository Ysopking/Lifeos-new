package app.lifeos.next.kernel

import app.lifeos.core.image.LocalImageTransformOperation
import app.lifeos.core.model.PhotonId

sealed interface LocalImageTransformExecutionResult {
    data class Transformed(
        val sourcePhotonId: PhotonId,
        val operations: List<LocalImageTransformOperation>,
        val output: PhotonSubmissionResult,
    ) : LocalImageTransformExecutionResult

    data class Blocked(val reason: String) : LocalImageTransformExecutionResult {
        init { require(reason.isNotBlank()) }
    }

    data class Failed(val message: String) : LocalImageTransformExecutionResult {
        init { require(message.isNotBlank()) }
    }
}
