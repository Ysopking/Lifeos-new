package app.lifeos.core.runtime.goal

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.PhotonId
import java.time.Instant

@JvmInline
value class GoalTransitionId(val value: String) {
    init {
        require(value.startsWith(PREFIX)) { "Invalid goal transition id prefix" }
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}"))) {
            "Invalid goal transition id digest"
        }
    }

    override fun toString(): String = value

    companion object {
        const val PREFIX = "goal-transition:"
    }
}

data class GoalPlanTransition(
    val id: GoalTransitionId,
    val planId: GoalPlanId,
    val predecessorId: GoalTransitionId?,
    val stepId: GoalStepId,
    val fromState: GoalStepState,
    val toState: GoalStepState,
    val reason: String,
    val sourceFingerprint: String,
    val decisionFingerprint: String? = null,
    val actionId: String? = null,
    val actionIdempotencyKey: String? = null,
    val outcomePhotonId: PhotonId? = null,
    val createdAt: Instant,
) {
    init {
        require(fromState != toState) { "Goal transition must change state" }
        require(reason.isNotBlank()) { "Goal transition reason must not be blank" }
        require(sourceFingerprint.isNotBlank()) { "Goal transition source fingerprint must not be blank" }
        require(decisionFingerprint == null || decisionFingerprint.isNotBlank())
        require(actionId == null || actionId.isNotBlank())
        require(actionIdempotencyKey == null || actionIdempotencyKey.isNotBlank())
        if (toState == GoalStepState.RUNNING) {
            require(!actionId.isNullOrBlank()) { "RUNNING transition requires action id" }
            require(!actionIdempotencyKey.isNullOrBlank()) {
                "RUNNING transition requires stable action idempotency key"
            }
        }
        if (toState == GoalStepState.COMPLETED || toState == GoalStepState.FAILED) {
            require(!actionId.isNullOrBlank()) { "Terminal execution transition requires action id" }
            require(outcomePhotonId != null) { "Terminal execution transition requires outcome photon" }
        }
        if (toState == GoalStepState.WAITING_EVIDENCE) {
            require(!decisionFingerprint.isNullOrBlank()) {
                "WAITING_EVIDENCE transition requires decision lineage"
            }
        }
        require(id == expectedId()) { "Goal transition id/content mismatch" }
    }

    fun contentFingerprint(): String = fingerprint(
        planId = planId,
        predecessorId = predecessorId,
        stepId = stepId,
        fromState = fromState,
        toState = toState,
        reason = reason,
        sourceFingerprint = sourceFingerprint,
        decisionFingerprint = decisionFingerprint,
        actionId = actionId,
        actionIdempotencyKey = actionIdempotencyKey,
        outcomePhotonId = outcomePhotonId,
        createdAt = createdAt,
    )

    private fun expectedId(): GoalTransitionId =
        GoalTransitionId("${GoalTransitionId.PREFIX}${contentFingerprint()}")

    companion object {
        fun create(
            planId: GoalPlanId,
            predecessorId: GoalTransitionId?,
            stepId: GoalStepId,
            fromState: GoalStepState,
            toState: GoalStepState,
            reason: String,
            sourceFingerprint: String,
            decisionFingerprint: String? = null,
            actionId: String? = null,
            actionIdempotencyKey: String? = null,
            outcomePhotonId: PhotonId? = null,
            createdAt: Instant,
        ): GoalPlanTransition {
            val fingerprint = fingerprint(
                planId = planId,
                predecessorId = predecessorId,
                stepId = stepId,
                fromState = fromState,
                toState = toState,
                reason = reason,
                sourceFingerprint = sourceFingerprint,
                decisionFingerprint = decisionFingerprint,
                actionId = actionId,
                actionIdempotencyKey = actionIdempotencyKey,
                outcomePhotonId = outcomePhotonId,
                createdAt = createdAt,
            )
            return GoalPlanTransition(
                id = GoalTransitionId("${GoalTransitionId.PREFIX}$fingerprint"),
                planId = planId,
                predecessorId = predecessorId,
                stepId = stepId,
                fromState = fromState,
                toState = toState,
                reason = reason,
                sourceFingerprint = sourceFingerprint,
                decisionFingerprint = decisionFingerprint,
                actionId = actionId,
                actionIdempotencyKey = actionIdempotencyKey,
                outcomePhotonId = outcomePhotonId,
                createdAt = createdAt,
            )
        }

        private fun fingerprint(
            planId: GoalPlanId,
            predecessorId: GoalTransitionId?,
            stepId: GoalStepId,
            fromState: GoalStepState,
            toState: GoalStepState,
            reason: String,
            sourceFingerprint: String,
            decisionFingerprint: String?,
            actionId: String?,
            actionIdempotencyKey: String?,
            outcomePhotonId: PhotonId?,
            createdAt: Instant,
        ): String = StableFieldIds.fingerprint(
            "goal-transition/v1",
            planId.value,
            predecessorId?.value.orEmpty(),
            stepId.value,
            fromState.name,
            toState.name,
            reason,
            sourceFingerprint,
            decisionFingerprint.orEmpty(),
            actionId.orEmpty(),
            actionIdempotencyKey.orEmpty(),
            outcomePhotonId?.value.orEmpty(),
            createdAt.toString(),
        )
    }
}
