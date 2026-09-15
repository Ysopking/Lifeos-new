package app.lifeos.core.runtime

import app.lifeos.core.model.ModuleOutcome
import app.lifeos.core.model.ModuleProcessingId
import app.lifeos.core.model.PhotonId

/** Explicit outcome ingestion boundary; utility changes only from evidence tied to a recorded processing id. */
class ModuleOutcomeLearning(
    private val evidence: ModuleEvidenceRuntime,
) {
    suspend fun observe(
        processingId: ModuleProcessingId,
        utilityMicros: Long,
        accepted: Boolean,
        evidencePhotonIds: List<PhotonId> = emptyList(),
    ) {
        val processing = evidence.processing(processingId)
            ?: error("Unknown module processing evidence: ${processingId.value}")
        evidence.recordOutcome(
            ModuleOutcome(
                processing = processing,
                utilityMicros = utilityMicros,
                accepted = accepted,
                evidencePhotonIds = evidencePhotonIds,
            ),
        )
    }
}
