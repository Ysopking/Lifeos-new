package app.lifeos.core.runtime.learning

import app.lifeos.core.field.StableFieldIds

data class LearningDriftPolicy(
    val minimumVerifiedSamples: Int = 3,
    val minimumQualityDrop: Double = 0.15,
    val maximumAdaptedMeanQuality: Double = 0.45,
) {
    init {
        require(minimumVerifiedSamples in 1..128)
        require(minimumQualityDrop.isFinite() && minimumQualityDrop in 0.0..1.0)
        require(maximumAdaptedMeanQuality.isFinite() && maximumAdaptedMeanQuality in 0.0..1.0)
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "learning-drift-policy/v1",
        minimumVerifiedSamples.toString(),
        java.lang.Double.toHexString(minimumQualityDrop),
        java.lang.Double.toHexString(maximumAdaptedMeanQuality),
    )
}

data class LearningRollbackProposal(
    val id: String,
    val target: LearningAdaptationTarget,
    val adaptationId: LearningAdaptationId,
    val baselineMeanQuality: Double,
    val adaptedMeanQuality: Double,
    val policyFingerprint: String,
    val sourceScoreIds: List<OutcomeScoreId>,
    val reason: String,
) {
    init {
        require(id.startsWith("learning-rollback-proposal:"))
        require(baselineMeanQuality in 0.0..1.0)
        require(adaptedMeanQuality in 0.0..1.0)
        require(policyFingerprint.isNotBlank())
        require(sourceScoreIds.isNotEmpty())
        require(sourceScoreIds == sourceScoreIds.distinct().sortedBy { it.value })
        require(reason.isNotBlank())
    }
}

/**
 * Deterministic negative-drift detector. It never mutates the ledger itself; it emits an auditable
 * rollback proposal which must still be executed through LearningAdaptationPlanner.rollbackLatest
 * using a distinct independently verified outcome score.
 */
class LearningDriftDetector(
    private val policy: LearningDriftPolicy = LearningDriftPolicy(),
) {
    fun evaluate(
        latestAdaptation: LearningAdaptation,
        baselineWindow: List<OutcomeScore>,
        adaptedWindow: List<OutcomeScore>,
    ): LearningRollbackProposal? {
        val baseline = verified(baselineWindow)
        val adapted = verified(adaptedWindow)
        if (baseline.size < policy.minimumVerifiedSamples || adapted.size < policy.minimumVerifiedSamples) {
            return null
        }
        require((baseline + adapted).map { it.id }.distinct().size == baseline.size + adapted.size) {
            "Learning drift windows must not reuse outcome scores"
        }
        val baselineMean = baseline.map { it.quality }.average()
        val adaptedMean = adapted.map { it.quality }.average()
        val drop = baselineMean - adaptedMean
        if (drop < policy.minimumQualityDrop || adaptedMean > policy.maximumAdaptedMeanQuality) return null

        val sourceIds = adapted.map { it.id }.distinct().sortedBy { it.value }
        val policyFingerprint = policy.fingerprint()
        val reason = "negative-drift:quality-drop:${java.lang.Double.toHexString(drop)}"
        val fingerprint = StableFieldIds.fingerprint(
            "learning-rollback-proposal/v1",
            latestAdaptation.target.stableKey,
            latestAdaptation.id.value,
            java.lang.Double.toHexString(baselineMean),
            java.lang.Double.toHexString(adaptedMean),
            policyFingerprint,
            reason,
            *sourceIds.map { "score:${it.value}" }.toTypedArray(),
        )
        return LearningRollbackProposal(
            id = "learning-rollback-proposal:$fingerprint",
            target = latestAdaptation.target,
            adaptationId = latestAdaptation.id,
            baselineMeanQuality = baselineMean,
            adaptedMeanQuality = adaptedMean,
            policyFingerprint = policyFingerprint,
            sourceScoreIds = sourceIds,
            reason = reason,
        )
    }

    private fun verified(scores: List<OutcomeScore>): List<OutcomeScore> = scores
        .filter { it.state == OutcomeScoreState.VERIFIED && it.adaptationAllowed }
        .sortedBy { it.id.value }
}
