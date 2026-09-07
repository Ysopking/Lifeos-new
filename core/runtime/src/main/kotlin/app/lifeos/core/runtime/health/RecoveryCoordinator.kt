package app.lifeos.core.runtime.health

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

class ComponentUnavailable(val node: HealthNode) : IllegalStateException("Component unavailable: ${node.id}")

/** One attempt per call; existing durable-task retry policy retains ownership of task retries. */
class RecoveryCoordinator(
    val graph: HealthGraph = HealthGraph(),
    val safeMode: SafeModeController = SafeModeController(),
    val quarantine: QuarantineRegistry = QuarantineRegistry(),
    private val classifier: FailureClassifier = FailureClassifier(),
    private val breakerFactory: () -> CircuitBreaker = { CircuitBreaker() },
) {
    private val transitionLock = Any()
    private val breakers = mutableMapOf<String, CircuitBreaker>()
    @Synchronized private fun breaker(node: HealthNode) = breakers.getOrPut(node.id, breakerFactory)

    suspend fun <T> execute(node: HealthNode, action: suspend () -> T): T {
        val circuit = breaker(node)
        val token = synchronized(transitionLock) {
            if (quarantine.contains(node)) throw ComponentUnavailable(node)
            val acquired = circuit.acquire() ?: throw ComponentUnavailable(node)
            if (circuit.state == CircuitBreaker.State.HALF_OPEN) {
                graph.record(node, HealthState.RECOVERING, "recovery-probe")
            }
            acquired
        }
        try {
            val result = action()
            synchronized(transitionLock) {
                if (!quarantine.contains(node) && circuit.success(token)) {
                    graph.record(node, HealthState.HEALTHY, "verified-operation")
                }
            }
            return result
        } catch (error: Exception) {
            if (error is CancellationException && error !is TimeoutCancellationException) {
                circuit.cancel(token)
                throw error
            }
            // An inner guarded operation owns its failure; do not also degrade the caller.
            if (error is ComponentUnavailable) {
                circuit.cancel(token)
                throw error
            }
            val kind = classifier.classify(error)
            synchronized(transitionLock) {
              if (circuit.failure(token)) {
                val isolate = kind == FailureKind.INVARIANT || kind == FailureKind.SECURITY
                if (isolate) quarantine.isolate(node)
                val state = when {
                    isolate -> HealthState.QUARANTINED
                    circuit.state == CircuitBreaker.State.OPEN -> HealthState.UNHEALTHY
                    else -> HealthState.DEGRADED
                }
                graph.record(node, state, kind.name)
                if (node.requiredForRuntime && isolate) safeMode.enter(node.id)
              }
            }
            throw error
        }
    }
}
