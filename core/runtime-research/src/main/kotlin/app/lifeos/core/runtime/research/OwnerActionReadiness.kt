package app.lifeos.core.runtime.research

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.world.WorldGap

enum class OwnerActionReadinessState {
    INFORMATION_REQUIRED,
    CAPABILITY_REQUIRED,
    OWNER_POLICY_REVIEW_REQUIRED,
}

data class OwnerActionReadiness(
    val contextFingerprint: String,
    val state: OwnerActionReadinessState,
    val openGapIds: List<String>,
    val fingerprint: String,
) {
    init {
        require(contextFingerprint.isNotBlank())
        require(openGapIds == openGapIds.distinct().sorted())
        require(
            fingerprint == StableFieldIds.fingerprint(
                "owner-action-readiness/v1",
                contextFingerprint,
                state.name,
                *openGapIds.toTypedArray(),
            )
        )
    }

    val informationStepPreferred: Boolean
        get() = state == OwnerActionReadinessState.INFORMATION_REQUIRED

    val ownerPolicyReviewRequired: Boolean
        get() = state == OwnerActionReadinessState.OWNER_POLICY_REVIEW_REQUIRED

    val executionAuthority: Boolean
        get() = false

    val policyAuthority: Boolean
        get() = false
}

/**
 * B498 information-first readiness boundary.
 *
 * Open perception/verification/consistency gaps keep the system in information acquisition.
 * Capability gaps remain capability-resolution work. Only a gap-free state may proceed to the
 * existing Owner Policy review boundary; this evaluator never authorizes execution.
 */
class OwnerActionReadinessEvaluator {
    fun evaluate(
        context: OwnerPersonalContextSnapshot,
        openWorldGaps: Collection<WorldGap>,
    ): OwnerActionReadiness {
        val gaps = openWorldGaps
            .distinctBy { it.id }
            .sortedBy { it.id }

        val state = when {
            gaps.any {
                it is WorldGap.Perception ||
                    it is WorldGap.Verification ||
                    it is WorldGap.Consistency
            } -> OwnerActionReadinessState.INFORMATION_REQUIRED

            gaps.any { it is WorldGap.Capability } ->
                OwnerActionReadinessState.CAPABILITY_REQUIRED

            else -> OwnerActionReadinessState.OWNER_POLICY_REVIEW_REQUIRED
        }
        val ids = gaps.map { it.id }
        val fingerprint = StableFieldIds.fingerprint(
            "owner-action-readiness/v1",
            context.fingerprint,
            state.name,
            *ids.toTypedArray(),
        )
        return OwnerActionReadiness(
            contextFingerprint = context.fingerprint,
            state = state,
            openGapIds = ids,
            fingerprint = fingerprint,
        )
    }
}
