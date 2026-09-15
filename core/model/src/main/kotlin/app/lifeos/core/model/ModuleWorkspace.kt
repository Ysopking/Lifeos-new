package app.lifeos.core.model

/** Read-only projection suitable for workspace/UI surfaces. */
data class ModuleWorkspaceEntry(
    val module: ModuleIdentity,
    val latestProcessingId: ModuleProcessingId?,
    val latestTraceId: CausalTraceId?,
    val outputPhotonIds: List<PhotonId>,
    val utility: ModuleUtilitySnapshot,
) {
    init {
        require(utility.module.stableFingerprint == module.stableFingerprint) {
            "Workspace utility must describe the same module"
        }
        require(outputPhotonIds.distinct().size == outputPhotonIds.size) { "Workspace output ids must be unique" }
        require((latestProcessingId == null) == (latestTraceId == null)) {
            "Latest processing and trace must be present together"
        }
    }
}

data class ModuleWorkspaceSnapshot(
    val entries: List<ModuleWorkspaceEntry>,
) {
    init {
        require(entries.map { it.module.stableFingerprint }.distinct().size == entries.size) {
            "Workspace may contain each module implementation only once"
        }
    }

    val orderedEntries: List<ModuleWorkspaceEntry>
        get() = entries.sortedBy { it.module.stableFingerprint }
}
