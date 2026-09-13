package app.lifeos.next.kernel

import app.lifeos.core.image.LocalImageTransformOperation
import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.artifact.OwnerAssetReviewCandidateId

sealed interface LocalImageTransformExecutionResult {
    data class Transformed(
        val sourcePhotonId: PhotonId,
        val operations: List<LocalImageTransformOperation>,
        val output: PhotonSubmissionResult,
        val ownerReviewCandidateId: OwnerAssetReviewCandidateId? = null,
    ) : LocalImageTransformExecutionResult

    data class Blocked(val reason: String) : LocalImageTransformExecutionResult {
        init { require(reason.isNotBlank()) }
    }

    data class Failed(val message: String) : LocalImageTransformExecutionResult {
        init { require(message.isNotBlank()) }
    }
}
