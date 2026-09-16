package app.lifeos.core.runtime

import app.lifeos.core.model.CognitiveEvent
import app.lifeos.core.model.CognitiveEventStore

/** Verified snapshot plus the strictly contiguous event tail needed to reach current state. */
data class CognitiveRehydrationPlan(
    val snapshot: CognitiveSnapshot?,
    val tailEvents: List<CognitiveEvent>,
) {
    val baseEventSequence: Long get() = snapshot?.eventSequence ?: 0L
    val currentEventSequence: Long get() = tailEvents.lastOrNull()?.sequence ?: baseEventSequence

    init {
        var expected = (snapshot?.eventSequence ?: 0L) + 1L
        tailEvents.forEach { event ->
            require(event.sequence == expected) { "Snapshot event tail is not contiguous" }
            expected++
        }
    }
}

/**
 * Canonical boot/recovery seam: select the newest verified snapshot, then ask the authoritative event store
 * only for events after that snapshot. Snapshot payload decoding remains owned by the world-state codec.
 */
class CognitiveSnapshotTailRehydrator(
    private val eventStore: CognitiveEventStore,
    private val compactor: SnapshotCompactor = SnapshotCompactor(),
) {
    suspend fun plan(
        candidates: Collection<Pair<CognitiveSnapshot, SnapshotManifest>>,
    ): CognitiveRehydrationPlan {
        val replay = compactor.selectLatestVerified(candidates)
        val tail = eventStore.eventsAfter(replay.eventsAfterSequence)
        return CognitiveRehydrationPlan(
            snapshot = replay.snapshot,
            tailEvents = tail,
        )
    }

    /**
     * Reconstructs one state authority. The snapshot restores a state baseline; every subsequent immutable
     * event is folded exactly once in sequence order. No parallel projection is created here.
     */
    suspend fun <T> rehydrate(
        candidates: Collection<Pair<CognitiveSnapshot, SnapshotManifest>>,
        emptyState: () -> T,
        restoreSnapshot: (CognitiveSnapshot) -> T,
        applyEvent: (T, CognitiveEvent) -> T,
    ): T {
        val plan = plan(candidates)
        var state = plan.snapshot?.let(restoreSnapshot) ?: emptyState()
        plan.tailEvents.forEach { event -> state = applyEvent(state, event) }
        return state
    }
}
