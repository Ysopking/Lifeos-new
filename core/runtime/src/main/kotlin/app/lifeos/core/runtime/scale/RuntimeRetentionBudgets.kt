package app.lifeos.core.runtime.scale

/**
 * Cross-layer architectural retention limits used by the M216 scale contract.
 *
 * These are object-count bounds rather than timing assertions, so they remain stable across CI
 * machines. Durable state may grow beyond these values; hot/runtime presentation state may not.
 */
object RuntimeRetentionBudgets {
    const val MAX_BOOTSTRAP_RETAINED_PHOTONS = 4_096
    const val MAX_PRESENTATION_VISIBLE_ITEMS = 20_000
}

data class ScaleRuntimeEvidence(
    val corpusFingerprint: String,
    val durablePhotonCount: Int,
    val retainedPhotonBudget: Int,
    val minimumColdPhotonCount: Int,
    val uiItemCount: Int,
    val presentationVisibleBudget: Int,
    val presentationTruncated: Boolean,
) {
    init {
        require(corpusFingerprint.length == 64)
        require(durablePhotonCount >= 0)
        require(retainedPhotonBudget > 0)
        require(minimumColdPhotonCount >= 0)
        require(uiItemCount >= 0)
        require(presentationVisibleBudget > 0)
    }
}

fun DeterministicScaleCorpus.runtimeEvidence(): ScaleRuntimeEvidence {
    val descriptor = descriptor()
    val retained = minOf(
        descriptor.photonCount,
        RuntimeRetentionBudgets.MAX_BOOTSTRAP_RETAINED_PHOTONS,
    )
    return ScaleRuntimeEvidence(
        corpusFingerprint = descriptor.fingerprint,
        durablePhotonCount = descriptor.photonCount,
        retainedPhotonBudget = retained,
        minimumColdPhotonCount = descriptor.photonCount - retained,
        uiItemCount = descriptor.uiItemCount,
        presentationVisibleBudget = RuntimeRetentionBudgets.MAX_PRESENTATION_VISIBLE_ITEMS,
        presentationTruncated =
            descriptor.uiItemCount > RuntimeRetentionBudgets.MAX_PRESENTATION_VISIBLE_ITEMS,
    )
}
