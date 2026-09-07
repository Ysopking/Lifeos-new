package app.lifeos.core.runtime.health

import app.lifeos.core.runtime.LifeOsRuntime
import app.lifeos.core.runtime.RuntimeFailure
import app.lifeos.core.runtime.RuntimeFailureCategory
import app.lifeos.core.runtime.RuntimeState
import app.lifeos.core.runtime.RuntimeStatus
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Process-lifetime projection of runtime state into the HealthGraph. */
class RuntimeHealthMonitor(
    private val scope: CoroutineScope,
    private val runtime: LifeOsRuntime,
    private val graph: HealthGraph,
    private val nodeId: HealthNodeId = HealthNodeId("runtime"),
    private val now: () -> Instant = Instant::now,
) {
    private val lock = Any()
    private var job: Job? = null

    fun start(): Job = synchronized(lock) {
        job?.takeIf { it.isActive } ?: scope.launch {
            graph.register(nodeId, HealthScope.RUNTIME)
            runtime.state.collect { state -> observe(state) }
        }.also { job = it }
    }

    fun stop() = synchronized(lock) {
        job?.cancel()
        job = null
    }

    private suspend fun observe(state: RuntimeState) {
        when (state.status) {
            RuntimeStatus.RUNNING -> graph.recordHealthy(
                id = nodeId,
                source = "durable-runtime",
                message = "Runtime running",
                observedAt = now(),
            )

            RuntimeStatus.STOPPED -> graph.recordHealthy(
                id = nodeId,
                source = "durable-runtime",
                message = "Runtime stopped cleanly",
                observedAt = now(),
            )

            RuntimeStatus.FAILED -> {
                val failure = state.lastFailure ?: RuntimeFailure(
                    category = RuntimeFailureCategory.UNKNOWN,
                    source = "durable-runtime",
                    message = "Runtime entered FAILED without structured failure",
                )
                graph.recordFailure(nodeId, failure, now())
            }

            RuntimeStatus.CREATED,
            RuntimeStatus.STARTING,
            RuntimeStatus.STOPPING -> Unit
        }
    }
}
