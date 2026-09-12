package app.lifeos.core.model

/**
 * Stable identity of a cognition-producing module implementation.
 * The implementation hash must change whenever executable behavior changes.
 */
data class ModuleIdentity(
    val moduleId: String,
    val version: String,
    val implementationHash: String,
    val capabilityIds: Set<String> = emptySet(),
) {
    init {
        require(moduleId.isNotBlank()) { "Module id must not be blank" }
        require(version.isNotBlank()) { "Module version must not be blank" }
        require(implementationHash.isNotBlank()) { "Module implementation hash must not be blank" }
        require(capabilityIds.none { it.isBlank() }) { "Capability ids must not be blank" }
    }

    val stableFingerprint: String
        get() = StableCognitiveIds.fingerprint(
            moduleId,
            version,
            implementationHash,
            *capabilityIds.sorted().toTypedArray(),
        )
}

data class ModuleProcessingRecord(
    val processingId: ModuleProcessingId,
    val traceId: CausalTraceId,
    val branchId: PhotonBranchId,
    val module: ModuleIdentity,
    val inputPhotonId: PhotonId,
    val inputRevision: Long,
    val context: DeterminismContext,
    val outputPhotonIds: List<PhotonId>,
    val outputStateHash: CognitiveStateHash,
) {
    init {
        require(inputRevision > 0) { "Input revision must be positive" }
        require(context.traceId == traceId) { "Determinism context must belong to the same trace" }
        require(outputPhotonIds.distinct().size == outputPhotonIds.size) { "Output photon ids must be unique" }
    }
}
