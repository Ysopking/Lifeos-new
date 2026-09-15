package app.lifeos.core.runtime

import app.lifeos.core.model.CognitiveTransaction
import app.lifeos.core.model.WorldState

data class ShadowWorldCandidate(val candidateId: String, val implementationFingerprint: String) {
    init { require(candidateId.isNotBlank() && implementationFingerprint.isNotBlank()) }
}

data class ShadowWorldEvaluation(
    val candidate: ShadowWorldCandidate,
    val replayEquivalent: Boolean,
    val recoveryEquivalent: Boolean,
    val invariantsSatisfied: Boolean,
    val latencyWithinBudget: Boolean,
    val resourcesWithinBudget: Boolean,
) {
    val promotionEligible: Boolean get() = replayEquivalent && recoveryEquivalent && invariantsSatisfied && latencyWithinBudget && resourcesWithinBudget
}

interface ShadowWorldEvaluator {
    suspend fun evaluate(base: WorldState, candidate: ShadowWorldCandidate, transactions: List<CognitiveTransaction>): ShadowWorldEvaluation
}
