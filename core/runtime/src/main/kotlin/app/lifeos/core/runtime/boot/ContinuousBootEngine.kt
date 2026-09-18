package app.lifeos.core.runtime.boot

import app.lifeos.core.runtime.world.CognitiveCycleId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ContinuousBootPhase {
    OPEN,
    COGNITION,
    WORLD_CONVERGENCE,
    ACTION_OUTCOME,
    LEARNING,
    FINALIZED,
}

data class ContinuousBootCycleState(
    val cycleId: CognitiveCycleId,
    val phase: ContinuousBootPhase,
    val worldSnapshotId: String? = null,
    val outcomeWorldSnapshotId: String? = null,
) {
    init {
        require(worldSnapshotId == null || worldSnapshotId.isNotBlank())
        require(outcomeWorldSnapshotId == null || outcomeWorldSnapshotId.isNotBlank())
        if (phase >= ContinuousBootPhase.WORLD_CONVERGENCE) {
            require(!worldSnapshotId.isNullOrBlank())
        }
        if (phase >= ContinuousBootPhase.LEARNING) {
            require(!outcomeWorldSnapshotId.isNullOrBlank())
        }
    }
}

/**
 * Single bounded process-level cognitive lifecycle. It owns ordering only; atomic world persistence
 * remains in BootEngineRuntime and external effects remain outside this class.
 */
class ContinuousBootEngine(
    private val bootEngine: BootEngineRuntime,
    private val learning: BootEngineLearningPhase,
) {
    private val mutex = Mutex()

    suspend fun openCycle(
        frozenInputs: BootEngineFrozenInputs,
    ): ContinuousBootCycleState = mutex.withLock {
        val cycle = bootEngine.startCycle(frozenInputs)
        ContinuousBootCycleState(
            cycleId = cycle.cycleId,
            phase = ContinuousBootPhase.OPEN,
        )
    }

    suspend fun markWorldConverged(
        state: ContinuousBootCycleState,
        worldSnapshotId: String,
    ): ContinuousBootCycleState = mutex.withLock {
        require(state.phase == ContinuousBootPhase.OPEN || state.phase == ContinuousBootPhase.COGNITION)
        state.copy(
            phase = ContinuousBootPhase.WORLD_CONVERGENCE,
            worldSnapshotId = worldSnapshotId,
        )
    }

    suspend fun markOutcome(
        state: ContinuousBootCycleState,
        outcomeWorldSnapshotId: String,
    ): ContinuousBootCycleState = mutex.withLock {
        require(state.phase == ContinuousBootPhase.WORLD_CONVERGENCE)
        state.copy(
            phase = ContinuousBootPhase.ACTION_OUTCOME,
            outcomeWorldSnapshotId = outcomeWorldSnapshotId,
        )
    }

    suspend fun learn(
        state: ContinuousBootCycleState,
    ): Pair<ContinuousBootCycleState, BootEngineLearningResult> = mutex.withLock {
        require(state.phase == ContinuousBootPhase.ACTION_OUTCOME)
        val binding = BootEngineLearningBinding(
            cycleId = state.cycleId,
            sourceWorldSnapshotId = requireNotNull(state.worldSnapshotId),
            outcomeWorldSnapshotId = requireNotNull(state.outcomeWorldSnapshotId),
        )
        val result = learning.processAvailableLearning(binding)
        state.copy(phase = ContinuousBootPhase.LEARNING) to result
    }

    suspend fun recover(): BootEngineRecoveryResult = mutex.withLock {
        bootEngine.recover()
    }
}
