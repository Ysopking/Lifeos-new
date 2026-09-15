package app.lifeos.core.runtime

import app.lifeos.core.model.CognitiveEvent
import app.lifeos.core.model.CognitiveEventStore
import app.lifeos.core.model.ProjectionValidity

enum class CognitiveProjectionKind { CONVERSATION, MEMORY, GOALS, MODULES, ARTIFACTS, WORLD_STATE, HEALTH }

data class CognitiveProjectionKey(val kind: CognitiveProjectionKind, val partition: String = "root") {
    init { require(partition.isNotBlank()) }
}

data class CognitiveProjection(
    val key: CognitiveProjectionKey,
    val throughSequence: Long,
    val stateFingerprint: String,
    val validity: ProjectionValidity,
) {
    init { require(throughSequence >= 0); require(stateFingerprint.isNotBlank()) }
}

/** Projection state is disposable; authoritative state is snapshot + ordered event stream. */
interface CognitiveProjectionReducer {
    fun accepts(event: CognitiveEvent): Boolean
    fun fold(current: CognitiveProjection?, event: CognitiveEvent): CognitiveProjection
}

class CognitiveProjectionEngine(
    private val eventStore: CognitiveEventStore,
    private val reducers: Map<CognitiveProjectionKind, CognitiveProjectionReducer>,
) {
    suspend fun rebuild(key: CognitiveProjectionKey, afterSequence: Long = 0): CognitiveProjection? {
        require(afterSequence >= 0)
        val reducer = requireNotNull(reducers[key.kind]) { "Missing reducer for ${key.kind}" }
        return eventStore.eventsAfter(afterSequence)
            .sortedBy { it.sequence }
            .filter(reducer::accepts)
            .fold<CognitiveEvent, CognitiveProjection?>(null) { projection, event -> reducer.fold(projection, event) }
    }
}
