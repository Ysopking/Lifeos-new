package app.lifeos.core.runtime.deepsearch

import app.lifeos.core.model.PhotonPhase
import java.util.Locale

/** Independent verification boundary between terminal search state and persisted result Photon. */
class DeepSearchMissionVerifier(
    private val projector: DeepSearchCheckpointResultProjector = DeepSearchCheckpointResultProjector(),
) {
    fun verify(
        definition: DeepSearchMissionDefinition,
        checkpoint: DeepSearchPlannerCheckpoint,
        product: DeepSearchMissionProduct,
    ): List<String> {
        val reasons = mutableListOf<String>()
        if (product.missionId != definition.id) reasons += "mission-id-mismatch"
        if (!projector.isTerminal(checkpoint)) reasons += "checkpoint-not-terminal"

        val expected = runCatching { projector.project(checkpoint) }.getOrNull()
        if (expected == null) {
            reasons += "checkpoint-result-unprojectable"
        } else if (product.result != expected) {
            reasons += "result-checkpoint-divergence"
        }

        if (product.result.requestId != checkpoint.request.id) reasons += "request-id-mismatch"
        if (product.result.workUnitsUsed > checkpoint.request.budget.maxWorkUnits) {
            reasons += "work-budget-exceeded"
        }

        val expectedEvidenceIds = product.result.evidence
            .mapNotNull { it.sourcePhotonId }
            .distinct()
            .sortedBy { it.value }
        if (product.evidencePhotonIds.distinct().sortedBy { it.value } != expectedEvidenceIds) {
            reasons += "evidence-binding-mismatch"
        }

        val photon = product.photon
        if (definition.sourcePhotonId !in photon.provenance.parentIds) reasons += "source-parent-missing"
        if (definition.goalPhotonId !in photon.provenance.parentIds) reasons += "goal-parent-missing"
        if (!photon.provenance.parentIds.containsAll(expectedEvidenceIds)) reasons += "evidence-parent-missing"

        val missionTag = "deepsearch-mission:${definition.id.value}"
        if (missionTag !in photon.tags) reasons += "mission-tag-missing"
        val statusTag = "deepsearch-status:${product.result.status.name.lowercase(Locale.ROOT)}"
        if (statusTag !in photon.tags) reasons += "status-tag-mismatch"
        val workTag = "deepsearch-work:${product.result.workUnitsUsed}"
        if (workTag !in photon.tags) reasons += "work-tag-mismatch"

        val expectedPhase = if (product.result.status == DeepSearchStatus.RESOLVED) {
            PhotonPhase.CONVERGED
        } else {
            PhotonPhase.REFLECTING
        }
        if (photon.phase != expectedPhase) reasons += "photon-phase-mismatch"
        if (photon.content.isBlank()) reasons += "empty-result-content"
        return reasons.distinct().sorted()
    }

    fun requireVerified(
        definition: DeepSearchMissionDefinition,
        checkpoint: DeepSearchPlannerCheckpoint,
        product: DeepSearchMissionProduct,
    ) {
        val reasons = verify(definition, checkpoint, product)
        require(reasons.isEmpty()) {
            "DeepSearch result verification failed: ${reasons.joinToString(",")}" 
        }
    }
}
