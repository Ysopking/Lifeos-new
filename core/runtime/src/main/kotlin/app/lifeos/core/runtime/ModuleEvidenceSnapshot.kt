package app.lifeos.core.runtime

import app.lifeos.core.model.ModuleCouplingRecord
import app.lifeos.core.model.ModuleOutcome
import app.lifeos.core.model.ModuleProcessingRecord
import app.lifeos.core.model.ModuleUtilitySnapshot

/** Durable representation consumed by platform-specific encrypted/atomic stores. */
data class ModuleEvidenceSnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val processing: List<ModuleProcessingRecord>,
    val couplings: List<ModuleCouplingRecord>,
    val outcomes: List<ModuleOutcome>,
    val utilities: List<ModuleUtilitySnapshot>,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "Unsupported module evidence schema: $schemaVersion" }
        require(processing.map { it.processingId.value }.distinct().size == processing.size) {
            "Snapshot processing ids must be unique"
        }
        require(couplings.map { it.coupling.stableFingerprint }.distinct().size == couplings.size) {
            "Snapshot coupling ids must be unique"
        }
        require(outcomes.map { it.stableFingerprint }.distinct().size == outcomes.size) {
            "Snapshot outcome ids must be unique"
        }
        require(utilities.map { it.module.stableFingerprint }.distinct().size == utilities.size) {
            "Snapshot utility modules must be unique"
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

interface ModuleEvidenceSnapshotStore {
    suspend fun load(): ModuleEvidenceSnapshot?
    suspend fun save(snapshot: ModuleEvidenceSnapshot)
}
