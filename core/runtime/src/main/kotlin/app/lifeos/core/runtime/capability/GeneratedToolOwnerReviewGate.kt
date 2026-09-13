package app.lifeos.core.runtime.capability

import app.lifeos.core.runtime.artifact.OwnerAssetReviewCandidateId

/**
 * Late-bound human review seam for one exact VERIFIED generated-tool revision.
 *
 * Implementations may persist/stage the generated source, but this interface itself grants no
 * execution or activation authority. Returning a candidate id means the tool must remain VERIFIED
 * until that exact candidate is approved by the private owner.
 */
fun interface GeneratedToolOwnerReviewGate {
    suspend fun stage(record: GeneratedToolRecord): OwnerAssetReviewCandidateId
}

object GeneratedToolOwnerReviewGateRegistry {
    @Volatile
    private var gate: GeneratedToolOwnerReviewGate? = null

    fun install(value: GeneratedToolOwnerReviewGate) {
        gate = value
    }

    fun currentOrNull(): GeneratedToolOwnerReviewGate? = gate
}
