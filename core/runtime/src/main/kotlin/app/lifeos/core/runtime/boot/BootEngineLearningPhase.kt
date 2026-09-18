package app.lifeos.core.runtime.boot

import app.lifeos.core.runtime.learning.ContinuousLearningCoordinator
import app.lifeos.core.runtime.learning.LearningCycleResult
import app.lifeos.core.runtime.learning.LearningLogicalKey
import app.lifeos.core.runtime.world.CognitiveCycleId

data class BootEngineLearningBinding(
    val cycleId: CognitiveCycleId,
    val sourceWorldSnapshotId: String,
    val outcomeWorldSnapshotId: String,
) {
    init {
        require(sourceWorldSnapshotId.isNotBlank())
        require(outcomeWorldSnapshotId.isNotBlank())
    }
}

data class BootEngineLearningResult(
    val binding: BootEngineLearningBinding,
    val cycle: LearningCycleResult,
    val logicalKeys: Set<LearningLogicalKey>,
) {
    init {
        require(
            logicalKeys.size == cycle.processed.size
        ) { "Every processed learning event must expose exactly one logical key" }
    }
}

class BootEngineLearningPhase(
    private val learning: ContinuousLearningCoordinator,
) {
    suspend fun processAvailableLearning(
        binding: BootEngineLearningBinding,
    ): BootEngineLearningResult {
        val result = learning.processAvailable()
        val keys = result.processed.mapTo(linkedSetOf()) { processed ->
            LearningLogicalKey(
                sourceId = processed.sourceId,
                sourceSequence = processed.sequence,
                eventFingerprint = processed.eventFingerprint,
            )
        }
        require(keys.size == result.processed.size) {
            "Duplicate logical learning key in one BootEngine phase"
        }
        return BootEngineLearningResult(
            binding = binding,
            cycle = result,
            logicalKeys = keys,
        )
    }
}
