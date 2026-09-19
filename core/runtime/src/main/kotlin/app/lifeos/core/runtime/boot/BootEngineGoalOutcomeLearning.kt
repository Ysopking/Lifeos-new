package app.lifeos.core.runtime.boot

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.world.WorldDimensionValue
import app.lifeos.core.field.world.WorldFieldVector
import app.lifeos.core.field.world.WorldNodeKind
import app.lifeos.core.field.world.WorldSignalDimension
import app.lifeos.core.field.world.WorldTargetRef
import app.lifeos.core.model.Photon
import app.lifeos.core.runtime.goal.GoalConvergenceCycleBinding
import app.lifeos.core.runtime.goal.GoalOutcomeLearningHook
import app.lifeos.core.runtime.world.WorldFormulaCoordinator
import app.lifeos.core.runtime.world.WorldFormulaExecutionState
import app.lifeos.core.runtime.world.WorldFormulaInputSnapshot
import app.lifeos.core.runtime.world.WorldFormulaRequest
import app.lifeos.core.runtime.world.WorldFormulaSnapshot
import app.lifeos.core.runtime.world.WorldFormulaStatus

data class BootEngineGoalLearningResult(
    val outcomeWorldSnapshot: WorldFormulaSnapshot,
    val learning: BootEngineLearningResult,
)

/**
 * Productive action outcomes re-enter the cognitive runtime as persisted WorldFormula evidence before
 * the BootEngine-owned learning phase advances its durable watermarks.
 *
 * This service never commits a productive world head and never grants execution authority. The
 * outcome snapshot is an evidence snapshot for learning, bound to the exact equation version that
 * was frozen for the source goal cycle.
 */
class BootEngineGoalOutcomeLearning(
    private val worldFormula: WorldFormulaCoordinator,
    private val learning: BootEngineLearningPhase,
) : GoalOutcomeLearningHook {
    override suspend fun learn(
        binding: GoalConvergenceCycleBinding,
        outcome: Photon,
        succeeded: Boolean,
    ) {
        process(binding, outcome, succeeded)
    }

    suspend fun process(
        binding: GoalConvergenceCycleBinding,
        outcome: Photon,
        succeeded: Boolean,
    ): BootEngineGoalLearningResult {
        val outcomeSnapshot = projectOutcome(binding, outcome, succeeded)
        val learningResult = learning.processAvailableLearning(
            BootEngineLearningBinding(
                cycleId = binding.cycleId,
                sourceWorldSnapshotId = binding.sourceWorldSnapshotId,
                outcomeWorldSnapshotId = outcomeSnapshot.id,
            )
        )
        return BootEngineGoalLearningResult(
            outcomeWorldSnapshot = outcomeSnapshot,
            learning = learningResult,
        )
    }

    private suspend fun projectOutcome(
        binding: GoalConvergenceCycleBinding,
        outcome: Photon,
        succeeded: Boolean,
    ): WorldFormulaSnapshot {
        val evidenceFingerprint = StableFieldIds.fingerprint(
            "goal-outcome-world-evidence/v1",
            binding.cycleId.value,
            binding.sourceWorldSnapshotId,
            binding.equationVersion,
            outcome.id.value,
            outcome.revision.toString(),
            outcome.provenance.source,
            outcome.provenance.actor,
            outcome.provenance.createdAt.toString(),
            outcome.content,
            succeeded.toString(),
        )
        val provenance = setOf(evidenceFingerprint)
        val confidence = outcome.confidence.coerceIn(0.0, 1.0)
        val input = WorldFormulaInputSnapshot(
            target = WorldTargetRef(
                kind = WorldNodeKind.OUTCOME,
                key = "${outcome.id.value}@${outcome.revision}",
            ),
            vector = WorldFieldVector(
                listOf(
                    WorldDimensionValue(
                        dimension = WorldSignalDimension.OUTCOME_ALIGNMENT,
                        value = if (succeeded) 1.0 else 0.0,
                        confidence = confidence,
                        provenanceFingerprints = provenance,
                    ),
                    WorldDimensionValue(
                        dimension = WorldSignalDimension.EVIDENCE_SUPPORT,
                        value = confidence,
                        confidence = confidence,
                        provenanceFingerprints = provenance,
                    ),
                    WorldDimensionValue(
                        dimension = WorldSignalDimension.RELIABILITY,
                        value = confidence,
                        confidence = confidence,
                        provenanceFingerprints = provenance,
                    ),
                    WorldDimensionValue(
                        dimension = WorldSignalDimension.GOAL_RELEVANCE,
                        value = 1.0,
                        confidence = confidence,
                        provenanceFingerprints = provenance,
                    ),
                    WorldDimensionValue(
                        dimension = WorldSignalDimension.TEMPORAL_FRESHNESS,
                        value = 1.0,
                        confidence = confidence,
                        provenanceFingerprints = provenance,
                    ),
                )
            ),
            sourceSnapshotFingerprint = StableFieldIds.fingerprint(
                "goal-outcome-world-input/v1",
                evidenceFingerprint,
            ),
        )
        val execution = worldFormula.evaluate(
            WorldFormulaRequest(
                inputs = listOf(input),
                interactions = emptyList(),
                equationVersion = binding.equationVersion,
                observedAt = outcome.provenance.createdAt,
                sourceTaskId = null,
                photonId = outcome.id,
            )
        )
        require(execution.state == WorldFormulaExecutionState.COMPLETED) {
            "Outcome WorldFormula persistence failed: ${execution.message}"
        }
        require(execution.status == WorldFormulaStatus.CONVERGED) {
            "Outcome WorldFormula did not converge: ${execution.status}"
        }
        return requireNotNull(execution.snapshot) {
            "Outcome WorldFormula completed without a persisted snapshot"
        }
    }
}
