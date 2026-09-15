package app.lifeos.core.model

@JvmInline
value class CognitiveTransactionId(val value: String) {
    init { require(value.isNotBlank()) { "Cognitive transaction id must not be blank" } }
}

enum class CognitiveTransactionState {
    OPEN,
    COMMITTED,
    ROLLED_BACK,
    FAILED,
}

data class CognitiveTransaction(
    val id: CognitiveTransactionId,
    val traceId: CausalTraceId,
    val branchId: PhotonBranchId,
    val inputPhotonId: PhotonId,
    val inputRevision: Long,
    val state: CognitiveTransactionState,
) {
    init { require(inputRevision > 0) { "Transaction input revision must be positive" } }

    val stableFingerprint: String
        get() = StableCognitiveIds.fingerprint(
            id.value,
            traceId.value,
            branchId.value,
            inputPhotonId.value,
            inputRevision.toString(),
            state.name,
        )
}

/** Cross-subsystem links are IDs only; transaction identity never owns duplicated domain state. */
data class CognitiveTransactionBinding(
    val transactionId: CognitiveTransactionId,
    val processingIds: List<ModuleProcessingId> = emptyList(),
    val outputPhotonIds: List<PhotonId> = emptyList(),
    val artifactRevisionIds: List<String> = emptyList(),
    val decisionIds: List<String> = emptyList(),
    val outcomeIds: List<String> = emptyList(),
) {
    init {
        require(processingIds.distinct().size == processingIds.size) { "Processing bindings must be unique" }
        require(outputPhotonIds.distinct().size == outputPhotonIds.size) { "Output bindings must be unique" }
        require(artifactRevisionIds.all { it.isNotBlank() } && artifactRevisionIds.distinct().size == artifactRevisionIds.size)
        require(decisionIds.all { it.isNotBlank() } && decisionIds.distinct().size == decisionIds.size)
        require(outcomeIds.all { it.isNotBlank() } && outcomeIds.distinct().size == outcomeIds.size)
    }
}
