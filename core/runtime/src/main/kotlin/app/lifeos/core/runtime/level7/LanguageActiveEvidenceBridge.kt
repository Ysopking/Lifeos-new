package app.lifeos.core.runtime.level7

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.language.GoalFrame
import app.lifeos.core.runtime.deepsearch.DeepSearchBudget

data class LanguageEvidencePlan(
    val gapFingerprint: String,
    val actions: List<EvidenceActionRequest>,
) {
    init {
        require(gapFingerprint.isNotBlank())
        require(actions.distinctBy { it.id }.size == actions.size)
    }

    fun selected(kind: EvidenceActionKind): Boolean =
        actions.any { it.kind == kind }
}

/**
 * Projects LIFEOS' deterministic interpretation quality into the existing ActiveEvidence planner.
 * It never executes network work itself and never grants action authority.
 */
class LanguageActiveEvidenceBridge(
    private val planner: ActiveEvidencePlanner = ActiveEvidencePlanner(),
) {
    fun plan(
        goal: GoalFrame,
        sourceCycleId: String,
        budget: DeepSearchBudget,
        externalAvailable: Boolean,
    ): LanguageEvidencePlan {
        require(sourceCycleId.isNotBlank())
        val quality = goal.interpretationQuality
        val uncertainty = (
            1.0 -
                maxOf(
                    goal.confidence,
                    quality.evidenceStrength,
                    quality.completeness,
                )
            ).coerceIn(0.0, 1.0)
        val ambiguityBoost = (quality.ambiguityCount * 0.08).coerceAtMost(0.24)
        val gapFingerprint = StableFieldIds.fingerprint(
            "language-active-evidence-gap/v1",
            goal.intent.name,
            goal.objective,
            java.lang.Double.toHexString(goal.confidence),
            java.lang.Double.toHexString(quality.evidenceStrength),
            java.lang.Double.toHexString(quality.completeness),
            quality.ambiguityCount.toString(),
            quality.contradictionCount.toString(),
        )

        val opportunities = buildList {
            add(
                EvidenceOpportunity(
                    kind = EvidenceActionKind.LOCAL_RETRIEVAL,
                    uncertaintyReduction = (0.60 + uncertainty * 0.20).coerceIn(0.0, 1.0),
                    goalRelevance = goal.confidence.coerceAtLeast(0.55),
                    sourceReliability = 1.0,
                    resourceCost = 0.30,
                    rationale = "Use bounded local Photon evidence before or alongside external evidence.",
                )
            )
            if (externalAvailable) {
                add(
                    EvidenceOpportunity(
                        kind = EvidenceActionKind.DEEP_SEARCH,
                        uncertaintyReduction = (0.82 + uncertainty * 0.10 + ambiguityBoost).coerceIn(0.0, 1.0),
                        goalRelevance = goal.confidence.coerceAtLeast(0.65),
                        sourceReliability = 0.80,
                        resourceCost = 0.34,
                        rationale = "Acquire bounded public evidence for an explicit SEARCH goal.",
                    )
                )
            }
        }

        val actions = planner.select(
            sourceCycleId = sourceCycleId,
            gapFingerprint = gapFingerprint,
            budgetFingerprint = budget.fingerprint(),
            opportunities = opportunities,
            limit = opportunities.size.coerceAtLeast(1),
        )
        return LanguageEvidencePlan(gapFingerprint, actions)
    }
}
