package app.lifeos.core.model

enum class CognitiveBranchStatus {
    CREATED,
    PROCESSING,
    READY_FOR_CONVERGENCE,
    REJECTED,
    CONVERGED,
}

data class CognitiveBranch(
    val branchId: PhotonBranchId,
    val traceId: CausalTraceId,
    val parentPhotonId: PhotonId,
    val parentRevision: Long,
    val ordinal: Int,
    val module: ModuleIdentity,
    val inputStateHash: CognitiveStateHash,
    val status: CognitiveBranchStatus = CognitiveBranchStatus.CREATED,
    val outputPhotonIds: List<PhotonId> = emptyList(),
    val outputStateHash: CognitiveStateHash? = null,
) {
    init {
        require(parentRevision > 0) { "Parent revision must be positive" }
        require(ordinal >= 0) { "Branch ordinal must not be negative" }
        require(outputPhotonIds.distinct().size == outputPhotonIds.size) { "Output photon ids must be unique" }
        require((status == CognitiveBranchStatus.CREATED || status == CognitiveBranchStatus.PROCESSING) || outputStateHash != null) {
            "Completed branch states require an output state hash"
        }
    }

    fun processing(): CognitiveBranch = copy(status = CognitiveBranchStatus.PROCESSING)

    fun ready(outputs: List<PhotonId>, stateHash: CognitiveStateHash): CognitiveBranch = copy(
        status = CognitiveBranchStatus.READY_FOR_CONVERGENCE,
        outputPhotonIds = outputs,
        outputStateHash = stateHash,
    )

    fun rejected(outputs: List<PhotonId>, stateHash: CognitiveStateHash): CognitiveBranch = copy(
        status = CognitiveBranchStatus.REJECTED,
        outputPhotonIds = outputs,
        outputStateHash = stateHash,
    )

    fun converged(): CognitiveBranch {
        require(status == CognitiveBranchStatus.READY_FOR_CONVERGENCE) {
            "Only a branch ready for convergence can be marked converged"
        }
        return copy(status = CognitiveBranchStatus.CONVERGED)
    }
}

enum class CognitiveConvergenceStatus {
    CONVERGED,
    UNRESOLVED,
    REJECTED,
}

data class CognitiveConvergenceRecord(
    val convergenceId: ConvergenceId,
    val traceId: CausalTraceId,
    val branchIds: List<PhotonBranchId>,
    val selectedBranchId: PhotonBranchId?,
    val status: CognitiveConvergenceStatus,
    val inputStateHash: CognitiveStateHash,
    val outputStateHash: CognitiveStateHash,
    val reasonFingerprint: String,
) {
    init {
        require(branchIds.isNotEmpty()) { "Convergence requires at least one branch" }
        require(branchIds.distinct().size == branchIds.size) { "Branch ids must be unique" }
        require(reasonFingerprint.isNotBlank()) { "Reason fingerprint must not be blank" }
        require(selectedBranchId == null || selectedBranchId in branchIds) {
            "Selected branch must be part of the convergence set"
        }
        require((status == CognitiveConvergenceStatus.CONVERGED) == (selectedBranchId != null)) {
            "Exactly converged records must identify a selected branch"
        }
    }

    val canonicalBranchIds: List<PhotonBranchId>
        get() = branchIds.distinct().sortedBy { it.value }
}
