package app.lifeos.core.runtime.goal

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.PhotonId

enum class GoalCognitiveCycleBindingState {
    WORLD_CONVERGED,
    OUTCOME_RECORDED,
    LEARNED,
}

data class GoalCognitiveCycleBindingRecord private constructor(
    val planId: GoalPlanId,
    val revision: Long,
    val sourceGoalPhotonId: PhotonId,
    val sourceGoalPhotonRevision: Long,
    val cycleBinding: GoalConvergenceCycleBinding,
    val state: GoalCognitiveCycleBindingState,
    val outcomePhotonId: PhotonId?,
    val outcomePhotonRevision: Long?,
    val outcomeWorldSnapshotId: String?,
    val learningWatermarkRevision: Long?,
    val fingerprint: String,
) {
    init {
        require(revision > 0L)
        require(sourceGoalPhotonRevision > 0L)
        require((outcomePhotonId == null) == (outcomePhotonRevision == null)) {
            "Outcome Photon id/revision must advance together"
        }
        require(outcomePhotonRevision == null || outcomePhotonRevision > 0L)
        if (state >= GoalCognitiveCycleBindingState.OUTCOME_RECORDED) {
            require(outcomePhotonId != null && outcomePhotonRevision != null)
        }
        if (state >= GoalCognitiveCycleBindingState.LEARNED) {
            require(!outcomeWorldSnapshotId.isNullOrBlank())
            require(learningWatermarkRevision != null && learningWatermarkRevision >= 0L)
        }
        require(fingerprint == expectedFingerprint()) {
            "Goal cognitive-cycle binding fingerprint does not match content"
        }
    }

    fun recordOutcome(
        outcome: app.lifeos.core.model.Photon,
    ): GoalCognitiveCycleBindingRecord {
        if (state >= GoalCognitiveCycleBindingState.OUTCOME_RECORDED) {
            require(outcomePhotonId == outcome.id && outcomePhotonRevision == outcome.revision) {
                "Goal cognitive-cycle binding already references another outcome"
            }
            return this
        }
        return create(
            planId = planId,
            revision = Math.addExact(revision, 1L),
            sourceGoalPhotonId = sourceGoalPhotonId,
            sourceGoalPhotonRevision = sourceGoalPhotonRevision,
            cycleBinding = cycleBinding,
            state = GoalCognitiveCycleBindingState.OUTCOME_RECORDED,
            outcomePhotonId = outcome.id,
            outcomePhotonRevision = outcome.revision,
            outcomeWorldSnapshotId = null,
            learningWatermarkRevision = null,
        )
    }

    fun markLearned(
        outcomeWorldSnapshotId: String,
        learningWatermarkRevision: Long,
    ): GoalCognitiveCycleBindingRecord {
        require(state >= GoalCognitiveCycleBindingState.OUTCOME_RECORDED) {
            "Goal cognitive-cycle binding must record the outcome before learning"
        }
        require(outcomeWorldSnapshotId.isNotBlank())
        require(learningWatermarkRevision >= 0L)
        if (state == GoalCognitiveCycleBindingState.LEARNED) {
            require(this.outcomeWorldSnapshotId == outcomeWorldSnapshotId)
            require(this.learningWatermarkRevision == learningWatermarkRevision)
            return this
        }
        return create(
            planId = planId,
            revision = Math.addExact(revision, 1L),
            sourceGoalPhotonId = sourceGoalPhotonId,
            sourceGoalPhotonRevision = sourceGoalPhotonRevision,
            cycleBinding = cycleBinding,
            state = GoalCognitiveCycleBindingState.LEARNED,
            outcomePhotonId = outcomePhotonId,
            outcomePhotonRevision = outcomePhotonRevision,
            outcomeWorldSnapshotId = outcomeWorldSnapshotId,
            learningWatermarkRevision = learningWatermarkRevision,
        )
    }

    private fun expectedFingerprint(): String = fingerprint(
        planId = planId,
        revision = revision,
        sourceGoalPhotonId = sourceGoalPhotonId,
        sourceGoalPhotonRevision = sourceGoalPhotonRevision,
        cycleBinding = cycleBinding,
        state = state,
        outcomePhotonId = outcomePhotonId,
        outcomePhotonRevision = outcomePhotonRevision,
        outcomeWorldSnapshotId = outcomeWorldSnapshotId,
        learningWatermarkRevision = learningWatermarkRevision,
    )

    companion object {
        fun converged(
            planId: GoalPlanId,
            sourceGoalPhotonId: PhotonId,
            sourceGoalPhotonRevision: Long,
            cycleBinding: GoalConvergenceCycleBinding,
        ): GoalCognitiveCycleBindingRecord = create(
            planId = planId,
            revision = 1L,
            sourceGoalPhotonId = sourceGoalPhotonId,
            sourceGoalPhotonRevision = sourceGoalPhotonRevision,
            cycleBinding = cycleBinding,
            state = GoalCognitiveCycleBindingState.WORLD_CONVERGED,
            outcomePhotonId = null,
            outcomePhotonRevision = null,
            outcomeWorldSnapshotId = null,
            learningWatermarkRevision = null,
        )

        fun restore(
            planId: GoalPlanId,
            revision: Long,
            sourceGoalPhotonId: PhotonId,
            sourceGoalPhotonRevision: Long,
            cycleBinding: GoalConvergenceCycleBinding,
            state: GoalCognitiveCycleBindingState,
            outcomePhotonId: PhotonId?,
            outcomePhotonRevision: Long?,
            outcomeWorldSnapshotId: String?,
            learningWatermarkRevision: Long?,
            fingerprint: String,
        ): GoalCognitiveCycleBindingRecord = GoalCognitiveCycleBindingRecord(
            planId = planId,
            revision = revision,
            sourceGoalPhotonId = sourceGoalPhotonId,
            sourceGoalPhotonRevision = sourceGoalPhotonRevision,
            cycleBinding = cycleBinding,
            state = state,
            outcomePhotonId = outcomePhotonId,
            outcomePhotonRevision = outcomePhotonRevision,
            outcomeWorldSnapshotId = outcomeWorldSnapshotId,
            learningWatermarkRevision = learningWatermarkRevision,
            fingerprint = fingerprint,
        )

        private fun create(
            planId: GoalPlanId,
            revision: Long,
            sourceGoalPhotonId: PhotonId,
            sourceGoalPhotonRevision: Long,
            cycleBinding: GoalConvergenceCycleBinding,
            state: GoalCognitiveCycleBindingState,
            outcomePhotonId: PhotonId?,
            outcomePhotonRevision: Long?,
            outcomeWorldSnapshotId: String?,
            learningWatermarkRevision: Long?,
        ): GoalCognitiveCycleBindingRecord = GoalCognitiveCycleBindingRecord(
            planId = planId,
            revision = revision,
            sourceGoalPhotonId = sourceGoalPhotonId,
            sourceGoalPhotonRevision = sourceGoalPhotonRevision,
            cycleBinding = cycleBinding,
            state = state,
            outcomePhotonId = outcomePhotonId,
            outcomePhotonRevision = outcomePhotonRevision,
            outcomeWorldSnapshotId = outcomeWorldSnapshotId,
            learningWatermarkRevision = learningWatermarkRevision,
            fingerprint = fingerprint(
                planId = planId,
                revision = revision,
                sourceGoalPhotonId = sourceGoalPhotonId,
                sourceGoalPhotonRevision = sourceGoalPhotonRevision,
                cycleBinding = cycleBinding,
                state = state,
                outcomePhotonId = outcomePhotonId,
                outcomePhotonRevision = outcomePhotonRevision,
                outcomeWorldSnapshotId = outcomeWorldSnapshotId,
                learningWatermarkRevision = learningWatermarkRevision,
            ),
        )

        private fun fingerprint(
            planId: GoalPlanId,
            revision: Long,
            sourceGoalPhotonId: PhotonId,
            sourceGoalPhotonRevision: Long,
            cycleBinding: GoalConvergenceCycleBinding,
            state: GoalCognitiveCycleBindingState,
            outcomePhotonId: PhotonId?,
            outcomePhotonRevision: Long?,
            outcomeWorldSnapshotId: String?,
            learningWatermarkRevision: Long?,
        ): String = StableFieldIds.fingerprint(
            "goal-cognitive-cycle-binding/v1",
            planId.value,
            revision.toString(),
            sourceGoalPhotonId.value,
            sourceGoalPhotonRevision.toString(),
            cycleBinding.cycleId.value,
            cycleBinding.sourceWorldSnapshotId,
            cycleBinding.equationVersion,
            state.name,
            outcomePhotonId?.value.orEmpty(),
            outcomePhotonRevision?.toString().orEmpty(),
            outcomeWorldSnapshotId.orEmpty(),
            learningWatermarkRevision?.toString().orEmpty(),
        )
    }
}

interface GoalCognitiveCycleBindingRepository {
    suspend fun load(planId: GoalPlanId): GoalCognitiveCycleBindingRecord?

    suspend fun compareAndSet(
        planId: GoalPlanId,
        expectedRevision: Long?,
        next: GoalCognitiveCycleBindingRecord,
    ): Boolean
}
