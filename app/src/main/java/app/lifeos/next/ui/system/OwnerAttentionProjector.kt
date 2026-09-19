package app.lifeos.next.ui.system

import app.lifeos.next.LifeOsToolCenterUiState
import app.lifeos.next.OwnerAssetReviewUiState
import app.lifeos.next.ui.components.RuntimeHealthLevel

data class OwnerAttentionUiState(
    val pendingAssetReviews: Int,
    val toolActions: Int,
    val runtimeNeedsAttention: Boolean,
) {
    init {
        require(pendingAssetReviews >= 0)
        require(toolActions >= 0)
    }

    val totalCount: Int
        get() = pendingAssetReviews + toolActions + if (runtimeNeedsAttention) 1 else 0

    val hasAttention: Boolean
        get() = totalCount > 0
}

/**
 * Read-only owner-attention projection over already authoritative UI/runtime evidence.
 *
 * It deliberately excludes "Heute" work because those actions are already visible in the primary
 * surface. The global System affordance only lights up for work otherwise hidden behind System:
 * asset approvals, generated-tool lifecycle decisions, or a degraded/failed runtime.
 */
object OwnerAttentionProjector {
    fun project(
        assets: OwnerAssetReviewUiState,
        tools: LifeOsToolCenterUiState,
        runtimeLevel: RuntimeHealthLevel,
    ): OwnerAttentionUiState {
        val workspace = tools.workspace
        val toolActions = workspace?.let { current ->
            current.gaps.count { it.approvalEligible } +
                current.trialTools.count { it.activationEligible } +
                current.attentionTools.size
        } ?: 0

        return projectCounts(
            pendingAssetReviews = assets.pendingCount,
            toolActions = toolActions,
            runtimeLevel = runtimeLevel,
        )
    }

    internal fun projectCounts(
        pendingAssetReviews: Int,
        toolActions: Int,
        runtimeLevel: RuntimeHealthLevel,
    ): OwnerAttentionUiState = OwnerAttentionUiState(
        pendingAssetReviews = pendingAssetReviews,
        toolActions = toolActions,
        runtimeNeedsAttention = runtimeLevel == RuntimeHealthLevel.DEGRADED ||
            runtimeLevel == RuntimeHealthLevel.FAILED,
    )
}
