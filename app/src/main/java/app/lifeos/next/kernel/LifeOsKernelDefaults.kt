package app.lifeos.next.kernel

import app.lifeos.core.runtime.cognition.CognitiveWorkBudget

internal object LifeOsKernelDefaults {
    const val BUILTIN_EXTENSION_SNAPSHOT_ID = "extension-registry:builtin-baseline"
    const val PRIVATE_OWNER_ACTOR_ID = "private-owner"
    const val OWNER_ASSET_REVIEW_PENDING = "awaiting-owner-review"

    val FAST_CHAT_BACKGROUND_BUDGET = CognitiveWorkBudget(
        maxDurationMs = 5_000,
        maxModuleInvocations = 4,
        maxNewPhotons = 4,
        maxNetworkCalls = 0,
    )
    val LIVE_SUBMISSION_BUDGET = CognitiveWorkBudget(
        maxDurationMs = 30_000,
        maxModuleInvocations = 16,
        maxNewPhotons = 16,
        maxNetworkCalls = 0,
    )
}
