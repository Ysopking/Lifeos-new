package app.lifeos.core.runtime

import app.lifeos.core.model.ModuleProcessingId
import app.lifeos.core.model.ModuleWorkspaceSnapshot
import app.lifeos.core.model.PhotonId

/** Stable application-facing seam for B16 learning and B17 workspace surfaces. */
class ModuleWorkspaceService(
    private val runtime: CausalCognitiveRuntime,
) {
    private val learning = ModuleOutcomeLearning(runtime.evidenceRuntime())

    suspend fun snapshot(): ModuleWorkspaceSnapshot = runtime.moduleWorkspace()

    suspend fun recordOutcome(
        processingId: ModuleProcessingId,
        utilityMicros: Long,
        accepted: Boolean,
        evidencePhotonIds: List<PhotonId> = emptyList(),
    ) {
        learning.observe(processingId, utilityMicros, accepted, evidencePhotonIds)
    }

    suspend fun verifyRecoveryReplay(): Boolean = runtime.verifyRecordedReplay()
}
