package app.lifeos.core.runtime.life

import app.lifeos.core.model.StableCognitiveIds
import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.capability.CapabilityGapType
import app.lifeos.core.runtime.capability.CapabilityId

enum class CapabilityExpansionStage {
    USE_EXISTING,
    GENESIS_PROPOSAL,
    BUILDSTUDIO_CANDIDATE,
    CANARY_REVIEW,
    OWNER_PROMOTION,
}

data class CreativeCapabilityRequest(
    val capabilityId: CapabilityId,
    val description: String,
    val existingProviderReady: Boolean,
    val sourceCanBeGeneratedLocally: Boolean,
) {
    init {
        require(description.isNotBlank())
    }
}

data class CapabilityExpansionPlan(
    val request: CreativeCapabilityRequest,
    val stages: List<CapabilityExpansionStage>,
    val activationAllowed: Boolean,
    val fingerprint: String,
)

/**
 * Block F creative-workshop seam. Planning can reach Genesis/BuildStudio, but generated work stays
 * non-activating until the already existing canary and owner-promotion authorities approve it.
 */
class CreativeCapabilityOrchestrator {
    fun plan(request: CreativeCapabilityRequest): CapabilityExpansionPlan {
        val stages = when {
            request.existingProviderReady -> listOf(CapabilityExpansionStage.USE_EXISTING)
            request.sourceCanBeGeneratedLocally -> listOf(
                CapabilityExpansionStage.GENESIS_PROPOSAL,
                CapabilityExpansionStage.CANARY_REVIEW,
                CapabilityExpansionStage.OWNER_PROMOTION,
            )
            else -> listOf(
                CapabilityExpansionStage.GENESIS_PROPOSAL,
                CapabilityExpansionStage.BUILDSTUDIO_CANDIDATE,
                CapabilityExpansionStage.CANARY_REVIEW,
                CapabilityExpansionStage.OWNER_PROMOTION,
            )
        }
        return CapabilityExpansionPlan(
            request = request,
            stages = stages,
            activationAllowed = request.existingProviderReady,
            fingerprint = StableCognitiveIds.fingerprint(
                "capability-expansion-plan/v1",
                request.capabilityId.value,
                request.description,
                request.existingProviderReady.toString(),
                request.sourceCanBeGeneratedLocally.toString(),
                *stages.map { it.name }.toTypedArray(),
            ),
        )
    }

    fun fromGap(gap: CapabilityGap, description: String): CapabilityExpansionPlan = plan(
        CreativeCapabilityRequest(
            capabilityId = gap.requirement.capabilityId,
            description = description,
            existingProviderReady = false,
            sourceCanBeGeneratedLocally = gap.type != CapabilityGapType.CONTRACT_MISMATCH,
        ),
    )
}
