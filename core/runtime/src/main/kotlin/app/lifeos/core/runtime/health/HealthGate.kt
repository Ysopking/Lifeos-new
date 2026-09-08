package app.lifeos.core.runtime.health

import app.lifeos.core.model.health.RuntimeProtectionState
import java.time.Instant

sealed interface HealthGateResult {
    data class Granted(val permit: HealthGatePermit) : HealthGateResult

    data class BlockedByProtection(
        val state: RuntimeProtectionState,
    ) : HealthGateResult

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
 * Single admission point for productive execution. Durable protection is checked first, then
 * process-local quarantine, then the generation-safe circuit breaker. Recovery/read-safe callers
 * may explicitly bypass protection/quarantine while still retaining circuit generation semantics.
 */
class HealthGate(
    private val circuitBreaker: CircuitBreaker,
    private val quarantineRegistry: QuarantineRegistry,
    private val protectionAdmission: ProtectionAdmission? = null,
) {
    suspend fun acquire(
        nodeId: HealthNodeId,
        at: Instant,
        purpose: HealthGatePurpose = HealthGatePurpose.NORMAL,
    ): HealthGateResult {
        when (val protection = protectionAdmission?.admit(nodeId, purpose)) {
            is ProtectionAdmissionDecision.Blocked -> {
                return HealthGateResult.BlockedByProtection(protection.state)
            }
            ProtectionAdmissionDecision.Allowed,
            null -> Unit
        }

        if (purpose == HealthGatePurpose.NORMAL) {
            quarantineRegistry.active(nodeId, at)?.let { entry ->
                return HealthGateResult.BlockedByQuarantine(entry)
            }
        }

        return when (val circuit = circuitBreaker.acquire(nodeId, at)) {
            is CircuitAcquireResult.Granted -> HealthGateResult.Granted(
                HealthGatePermit(circuit.permit),
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
