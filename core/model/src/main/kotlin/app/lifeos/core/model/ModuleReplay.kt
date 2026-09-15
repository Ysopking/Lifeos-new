package app.lifeos.core.model

/** Replay envelope used to prove that module processing can be reconstructed. */
data class ModuleReplayEnvelope(
    val processing: ModuleProcessingRecord,
    val expectedOutputStateHash: CognitiveStateHash,
    val expectedOutputPhotonIds: List<PhotonId>,
) {
    init {
        require(expectedOutputPhotonIds.distinct().size == expectedOutputPhotonIds.size) {
            "Replay output ids must be unique"
        }
        require(processing.outputStateHash == expectedOutputStateHash) {
            "Replay state hash must match recorded processing state"
        }
        require(processing.outputPhotonIds == expectedOutputPhotonIds) {
            "Replay photon outputs must match recorded processing outputs"
        }
    }

    fun matches(replayed: ModuleProcessingRecord): Boolean =
        replayed.module.stableFingerprint == processing.module.stableFingerprint &&
            replayed.traceId == processing.traceId &&
            replayed.branchId == processing.branchId &&
            replayed.inputPhotonId == processing.inputPhotonId &&
            replayed.inputRevision == processing.inputRevision &&
            replayed.context == processing.context &&
            replayed.outputPhotonIds == expectedOutputPhotonIds &&
            replayed.outputStateHash == expectedOutputStateHash

    fun requireMatches(replayed: ModuleProcessingRecord) {
        require(matches(replayed)) { "Module replay diverged from recorded processing" }
    }
}
