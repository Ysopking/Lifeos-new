package app.lifeos.core.runtime.thought

import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ThoughtGraphRehydrateReport(
    val restoredDeltaCount: Int,
    val revision: Long,
    val snapshot: ThoughtGraphSnapshot,
)

/**
 * Persistence-first owner of the append-only thought-graph history.
 *
 * New deltas are durably written before they affect in-memory state. Rehydration is an exact replay
 * of every readable immutable delta and fails closed if the repository reports any unreadable entry.
 */
class DurableThoughtGraph(
    private val repository: ThoughtGraphDeltaRepository,
    private val reducer: ThoughtGraphReducer = ThoughtGraphReducer(),
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(ThoughtGraphState())
    val state: StateFlow<ThoughtGraphState> = mutableState.asStateFlow()

    suspend fun append(
        delta: ThoughtGraphDelta,
        capturedAt: Instant = Instant.now(),
    ): ThoughtGraphApplyReport = mutex.withLock {
        val persisted = when (val write = repository.save(delta)) {
            is ThoughtGraphDeltaWriteResult.Stored -> write.delta
            is ThoughtGraphDeltaWriteResult.Duplicate -> write.delta
        }
        val report = reducer.apply(mutableState.value, persisted, capturedAt)
        mutableState.value = report.state
        report
    }

    suspend fun rehydrate(capturedAt: Instant = Instant.now()): ThoughtGraphRehydrateReport =
        mutex.withLock {
            val load = repository.loadReport()
            require(load.unreadableEntries.isEmpty()) {
                "Thought graph delta history contains unreadable entries: ${load.unreadableEntries.joinToString()}"
            }
            val replay = reducer.replay(load.deltas, capturedAt)
            mutableState.value = replay.state
            ThoughtGraphRehydrateReport(
                restoredDeltaCount = load.deltas.size,
                revision = replay.state.revision,
                snapshot = replay.snapshot,
            )
        }

    suspend fun snapshot(capturedAt: Instant = Instant.now()): ThoughtGraphSnapshot =
        mutex.withLock { reducer.snapshot(mutableState.value, capturedAt) }
}
