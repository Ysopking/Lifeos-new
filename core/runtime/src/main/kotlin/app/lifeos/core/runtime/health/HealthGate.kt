package app.lifeos.core.runtime.health

import java.time.Instant

sealed interface HealthGateResult {
    data class Granted(val permit: HealthGatePermit) : HealthGateResult

    data class BlockedByQuarantine(
        val entry: QuarantineEntry,
    ) : HealthGateResult

    data class BlockedByCircuit(
        val nodeId: HealthNodeId,
        val state: CircuitState,
        val retryAt: Instant?,
    ) : HealthGateResult
}

data class HealthGatePermit internal constructor(
    internal val circuitPermit: CircuitPermit,
) {
    val nodeId: HealthNodeId get() = circuitPermit.nodeId
    val probe: Boolean get() = circuitPermit.probe
}

/**
 * Single admission point for future worker/runtime integration.
 * Quarantine wins over circuit state; successful acquisition yields a permit
 * that must later be completed as success or failure.
 */
class HealthGate(
    private val circuitBreaker: CircuitBreaker,
    private val quarantineRegistry: QuarantineRegistry,
) {
    suspend fun acquire(nodeId: HealthNodeId, at: Instant): HealthGateResult {
        quarantineRegistry.active(nodeId, at)?.let { entry ->
            return HealthGateResult.BlockedByQuarantine(entry)
        }

        return when (val circuit = circuitBreaker.acquire(nodeId, at)) {
            is CircuitAcquireResult.Granted -> HealthGateResult.Granted(
                HealthGatePermit(circuit.permit)
            )

            is CircuitAcquireResult.Rejected -> HealthGateResult.BlockedByCircuit(
                nodeId = circuit.nodeId,
                state = circuit.state,
                retryAt = circuit.retryAt,
            )
        }
    }

    suspend fun onSuccess(permit: HealthGatePermit): Boolean =
        circuitBreaker.onSuccess(permit.circuitPermit)

    suspend fun onFailure(permit: HealthGatePermit, at: Instant): Boolean =
        circuitBreaker.onFailure(permit.circuitPermit, at)
}
