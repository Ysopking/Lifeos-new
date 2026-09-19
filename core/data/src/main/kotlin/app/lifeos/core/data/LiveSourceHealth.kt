package app.lifeos.core.data

import app.lifeos.core.model.StableCognitiveIds
import app.lifeos.core.runtime.RuntimeFailure
import app.lifeos.core.runtime.RuntimeFailureCategory
import app.lifeos.core.runtime.health.HealthGraph
import app.lifeos.core.runtime.health.HealthNodeId
import app.lifeos.core.runtime.health.HealthScope
import java.time.Instant

enum class LiveSourcePriority(val rank: Int) {
    BACKGROUND(0),
    NORMAL(1),
    HIGH(2),
    URGENT(3),
}

interface LiveSourceHealthReporter {
    suspend fun healthy(
        sourceId: LiveSourceId,
        observedAt: Instant,
        message: String,
    )

    suspend fun failed(
        sourceId: LiveSourceId,
        observedAt: Instant,
        category: RuntimeFailureCategory,
        message: String,
        recoverable: Boolean,
    )

    companion object {
        val NONE: LiveSourceHealthReporter = object : LiveSourceHealthReporter {
            override suspend fun healthy(
                sourceId: LiveSourceId,
                observedAt: Instant,
                message: String,
            ) = Unit

            override suspend fun failed(
                sourceId: LiveSourceId,
                observedAt: Instant,
                category: RuntimeFailureCategory,
                message: String,
                recoverable: Boolean,
            ) = Unit
        }
    }
}

/**
 * Projects source health into the existing process HealthGraph. It owns no parallel health state.
 */
class HealthGraphLiveSourceHealthReporter(
    private val graph: HealthGraph,
) : LiveSourceHealthReporter {
    override suspend fun healthy(
        sourceId: LiveSourceId,
        observedAt: Instant,
        message: String,
    ) {
        val id = liveSourceHealthNodeId(sourceId)
        graph.register(id, HealthScope.EXTERNAL_APP)
        graph.recordHealthy(
            id = id,
            source = "live-source:" + sourceId.value,
            message = message,
            observedAt = observedAt,
        )
    }

    override suspend fun failed(
        sourceId: LiveSourceId,
        observedAt: Instant,
        category: RuntimeFailureCategory,
        message: String,
        recoverable: Boolean,
    ) {
        val id = liveSourceHealthNodeId(sourceId)
        graph.register(id, HealthScope.EXTERNAL_APP)
        graph.recordFailure(
            id = id,
            failure = RuntimeFailure(
                category = category,
                source = "live-source:" + sourceId.value,
                message = message,
                recoverable = recoverable,
            ),
            observedAt = observedAt,
        )
    }
}

fun liveSourceHealthNodeId(sourceId: LiveSourceId): HealthNodeId = HealthNodeId(
    "live-source:" + StableCognitiveIds.fingerprint(
        "live-source-health/v1",
        sourceId.value,
    ).take(64)
)
