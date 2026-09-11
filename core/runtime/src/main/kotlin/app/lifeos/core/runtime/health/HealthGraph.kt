package app.lifeos.core.runtime.health

import app.lifeos.core.runtime.RuntimeFailure
import java.time.Instant
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-local health projection. Durable repair identity is owned by the V9 self-healing ledger;
 * this graph exposes a bounded observation stream so automatic healing can react without polling.
 */
class HealthGraph(
    private val classifier: FailureClassifier = FailureClassifier(),
    private val unhealthyAfterConsecutiveFailures: Int = 3,
    private val maxObservationsPerNode: Int = 64,
    private val now: () -> Instant = Instant::now,
) {
    init {
        require(unhealthyAfterConsecutiveFailures > 0) {
            "Unhealthy failure threshold must be positive"
        }
        require(maxObservationsPerNode > 0) {
            "Observation history limit must be positive"
        }
    }

    private val mutex = Mutex()
    private val nodes = linkedMapOf<HealthNodeId, HealthNode>()
    private val history = mutableMapOf<HealthNodeId, ArrayDeque<HealthObservation>>()
    private val mutableObservations = MutableSharedFlow<HealthObservation>(extraBufferCapacity = 128)

    val observations: SharedFlow<HealthObservation> = mutableObservations.asSharedFlow()

    suspend fun register(id: HealthNodeId, scope: HealthScope): HealthNode = mutex.withLock {
        nodes[id]?.let { existing ->
            require(existing.scope == scope) {
                "Health node ${id.value} already registered with scope ${existing.scope}"
            }
            return@withLock existing
        }

        HealthNode(id = id, scope = scope).also { nodes[id] = it }
    }

    suspend fun recordHealthy(
        id: HealthNodeId,
        source: String,
        message: String? = null,
        observedAt: Instant = now(),
    ): HealthNode = record(
        HealthObservation(
            nodeId = id,
            state = HealthState.HEALTHY,
            observedAt = observedAt,
            source = source,
            message = message,
        )
    )

    suspend fun recordFailure(
        id: HealthNodeId,
        failure: RuntimeFailure,
        observedAt: Instant = now(),
    ): HealthNode {
        val classification = classifier.classify(failure)
        return record(
            HealthObservation(
                nodeId = id,
                state = classification.suggestedState,
                observedAt = observedAt,
                source = failure.source,
                message = failure.message,
                classification = classification,
            )
        )
    }

    suspend fun record(observation: HealthObservation): HealthNode {
        val updated = mutex.withLock {
            val current = nodes[observation.nodeId]
                ?: HealthNode(
                    id = observation.nodeId,
                    scope = observation.classification?.scope ?: HealthScope.UNKNOWN,
                )

            val isFailure = observation.state == HealthState.DEGRADED ||
                observation.state == HealthState.UNHEALTHY
            val nextConsecutiveFailures = if (isFailure) current.consecutiveFailures + 1 else 0
            val nextTotalFailures = current.totalFailures + if (isFailure) 1 else 0

            val escalatedState = when {
                observation.state == HealthState.DEGRADED &&
                    nextConsecutiveFailures >= unhealthyAfterConsecutiveFailures -> HealthState.UNHEALTHY
                else -> observation.state
            }

            val next = current.copy(
                state = escalatedState,
                consecutiveFailures = nextConsecutiveFailures,
                totalFailures = nextTotalFailures,
                lastObservationAt = observation.observedAt,
                lastHealthyAt = if (observation.state == HealthState.HEALTHY) {
                    observation.observedAt
                } else {
                    current.lastHealthyAt
                },
                lastMessage = observation.message,
            )

            nodes[observation.nodeId] = next
            appendHistory(observation)
            next
        }
        mutableObservations.emit(observation)
        return updated
    }

    suspend fun node(id: HealthNodeId): HealthNode? = mutex.withLock { nodes[id] }

    suspend fun snapshot(capturedAt: Instant = now()): HealthSnapshot = mutex.withLock {
        HealthSnapshot(
            nodes = nodes.values.sortedBy { it.id.value },
            capturedAt = capturedAt,
        )
    }

    suspend fun recent(id: HealthNodeId, limit: Int = 16): List<HealthObservation> = mutex.withLock {
        require(limit > 0) { "Health observation limit must be positive" }
        history[id]
            ?.asReversed()
            ?.take(limit)
            .orEmpty()
    }

    private fun appendHistory(observation: HealthObservation) {
        val observations = history.getOrPut(observation.nodeId) { ArrayDeque() }
        observations.addLast(observation)
        while (observations.size > maxObservationsPerNode) {
            observations.removeFirst()
        }
    }
}
