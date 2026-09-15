package app.lifeos.core.model

/**
 * Canonical, deterministic coupling contract between cognition-producing modules.
 *
 * A coupling never invents a second module identity. Both endpoints are the
 * canonical [ModuleIdentity] instances already used by processing records.
 */
data class ModuleCoupling(
    val source: ModuleIdentity,
    val target: ModuleIdentity,
    val capabilityId: String,
    val inputPhotonId: PhotonId,
    val inputRevision: Long,
    val traceId: CausalTraceId,
    val branchId: PhotonBranchId,
) {
    init {
        require(source.moduleId != target.moduleId || source.stableFingerprint != target.stableFingerprint) {
            "Module coupling requires distinct module implementations"
        }
        require(capabilityId.isNotBlank()) { "Capability id must not be blank" }
        require(inputRevision > 0) { "Input revision must be positive" }
        require(capabilityId in target.capabilityIds) {
            "Target module must declare the coupled capability"
        }
    }

    val stableFingerprint: String
        get() = StableCognitiveIds.fingerprint(
            source.stableFingerprint,
            target.stableFingerprint,
            capabilityId,
            inputPhotonId.value,
            inputRevision.toString(),
            traceId.value,
            branchId.value,
        )
}

/** Evidence that a target module actually processed a coupling request. */
data class ModuleCouplingRecord(
    val coupling: ModuleCoupling,
    val processing: ModuleProcessingRecord,
) {
    init {
        require(processing.module.stableFingerprint == coupling.target.stableFingerprint) {
            "Processing module must be the coupling target"
        }
        require(processing.traceId == coupling.traceId) { "Processing trace must match coupling trace" }
        require(processing.branchId == coupling.branchId) { "Processing branch must match coupling branch" }
        require(processing.inputPhotonId == coupling.inputPhotonId) { "Processing input must match coupling input" }
        require(processing.inputRevision == coupling.inputRevision) { "Processing revision must match coupling revision" }
    }
}
