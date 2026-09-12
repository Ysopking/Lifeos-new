package app.lifeos.core.model

/**
 * Fan-in record for complementary module branches. This is deliberately separate from
 * winner-selection convergence: multiple modules may contribute different facts to one
 * integrated state without any branch being declared the exclusive winner.
 */
data class CognitiveIntegrationRecord(
    val convergenceId: ConvergenceId,
    val traceId: CausalTraceId,
    val branchIds: List<PhotonBranchId>,
    val contributingBranchIds: List<PhotonBranchId>,
    val inputStateHash: CognitiveStateHash,
    val outputStateHash: CognitiveStateHash,
    val integratedPhotonId: PhotonId,
    val reasonFingerprint: String,
) {
    init {
        require(branchIds.isNotEmpty()) { "Integration requires at least one branch" }
        require(branchIds.distinct().size == branchIds.size) { "Branch ids must be unique" }
        require(contributingBranchIds.isNotEmpty()) { "Integration requires contributors" }
        require(contributingBranchIds.distinct().size == contributingBranchIds.size) {
            "Contributing branch ids must be unique"
        }
        require(contributingBranchIds.all(branchIds::contains)) {
            "Contributing branches must belong to the integration set"
        }
        require(reasonFingerprint.isNotBlank()) { "Reason fingerprint must not be blank" }
    }

    val canonicalBranchIds: List<PhotonBranchId>
        get() = branchIds.sortedBy { it.value }

    val canonicalContributingBranchIds: List<PhotonBranchId>
        get() = contributingBranchIds.sortedBy { it.value }
}
