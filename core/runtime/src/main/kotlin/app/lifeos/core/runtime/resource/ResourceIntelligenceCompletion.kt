package app.lifeos.core.runtime.resource

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.trace.DecisionTraceId
import java.time.Instant

/**
 * V16 durable identity tying a resource reservation to the exact operation and V15 trace.
 * The binding is observational with respect to policy/authority: it cannot grant work.
 */
data class ResourceExecutionBinding(
    val traceId: DecisionTraceId,
    val domain: ResourceBudgetDomain,
    val operationId: String,
    val accountId: ResourceBudgetAccountId,
    val reservationId: ResourceBudgetReservationId,
    val authoritativeStateId: String?,
    val revision: Long,
    val boundAt: Instant,
) {
    init {
        require(operationId.isNotBlank())
        require(authoritativeStateId == null || authoritativeStateId.isNotBlank())
        require(revision > 0L)
    }
}

data class ExecutionMeasurement(
    val operationId: String,
    val measuredAt: Instant,
    val elapsedMillis: Long,
    val workUnits: Long,
    val peakMemoryBytes: Long,
    val ioBytes: Long,
    val networkBytes: Long,
    val candidates: Long,
    val thermalState: HardwareThermalState,
    val batteryFraction: Double?,
    val outcomeCode: String,
    val utility: Double?,
) {
    init {
        require(operationId.isNotBlank())
        require(
            listOf(
                elapsedMillis,
                workUnits,
                peakMemoryBytes,
                ioBytes,
                networkBytes,
                candidates,
            ).all { it >= 0L }
        )
        require(batteryFraction == null || batteryFraction.isFinite() && batteryFraction in 0.0..1.0)
        require(outcomeCode.isNotBlank())
        require(utility == null || utility.isFinite() && utility in 0.0..1.0)
    }

    fun asUsage(): ResourceBudgetUsage = ResourceBudgetUsage(
        elapsedMillis = elapsedMillis,
        workUnits = workUnits,
        memoryBytes = peakMemoryBytes,
        ioBytes = ioBytes,
        networkBytes = networkBytes,
        candidates = candidates,
    )
}

enum class ExecutionTelemetryClass {
    PRODUCTIVE,
    CONTROL_PLANE,
}

/**
 * Exact execution evidence bound to the decision inputs that produced it. The measurement itself
 * remains the existing V16 [ExecutionMeasurement]; this wrapper adds scheduling provenance rather
 * than introducing a parallel telemetry model.
 */
data class ExecutionSample(
    val measurement: ExecutionMeasurement,
    val domain: ResourceBudgetDomain,
    val operationKind: String,
    val executionClass: HardwareExecutionClass,
    val workGraphId: String?,
    val workNodeId: String?,
    val hardwareFingerprint: String,
    val executionPlanFingerprint: String,
    val strategyFingerprint: String,
    val learningProfileFingerprint: String,
    val queueWaitMillis: Long,
    val cpuTimeMillis: Long?,
    val parallelism: Int,
    val batchSize: Int,
    val reused: Boolean,
    val telemetryClass: ExecutionTelemetryClass = ExecutionTelemetryClass.PRODUCTIVE,
) {
    init {
        require(operationKind.isNotBlank())
        require(workGraphId == null || workGraphId.isNotBlank())
        require(workNodeId == null || workNodeId.isNotBlank())
        require(workNodeId == null || workGraphId != null) { "Work node id requires work graph id" }
        require(hardwareFingerprint.isNotBlank())
        require(executionPlanFingerprint.isNotBlank())
        require(strategyFingerprint.isNotBlank())
        require(learningProfileFingerprint.isNotBlank())
        require(queueWaitMillis >= 0L)
        require(cpuTimeMillis == null || cpuTimeMillis >= 0L)
        require(parallelism > 0)
        require(batchSize > 0)
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "execution-sample/v1",
        measurement.operationId,
        measurement.measuredAt.toString(),
        measurement.elapsedMillis.toString(),
        measurement.workUnits.toString(),
        measurement.peakMemoryBytes.toString(),
        measurement.ioBytes.toString(),
        measurement.networkBytes.toString(),
        measurement.candidates.toString(),
        measurement.thermalState.name,
        measurement.batteryFraction?.let(java.lang.Double::toHexString).orEmpty(),
        measurement.outcomeCode,
        measurement.utility?.let(java.lang.Double::toHexString).orEmpty(),
        domain.name,
        operationKind,
        executionClass.name,
        workGraphId.orEmpty(),
        workNodeId.orEmpty(),
        hardwareFingerprint,
        executionPlanFingerprint,
        strategyFingerprint,
        learningProfileFingerprint,
        queueWaitMillis.toString(),
        cpuTimeMillis?.toString().orEmpty(),
        parallelism.toString(),
        batchSize.toString(),
        reused.toString(),
        telemetryClass.name,
    )
}

/**
 * Learned estimates are hints only. They are always clamped to the caller supplied hard ceiling.
 */
data class SoftCostEstimate(
    val domain: ResourceBudgetDomain,
    val operationKind: String,
    val estimated: ResourceBudgetUsage,
    val samples: Long,
    val confidence: Double,
) {
    init {
        require(operationKind.isNotBlank())
        require(samples >= 0L)
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }

    fun conservativeReservation(hardCeiling: ResourceBudgetQuota): ResourceBudgetUsage =
        estimated.cappedBy(hardCeiling)
}

data class ResourceFairnessLane(
    val domain: ResourceBudgetDomain,
    val queuedOperations: Long,
    val servedOperations: Long,
    val consecutiveDeferrals: Long,
    val lastServedAt: Instant?,
) {
    init {
        require(queuedOperations >= 0L)
        require(servedOperations >= 0L)
        require(consecutiveDeferrals >= 0L)
    }
}

data class ResourceFairnessState(
    val revision: Long,
    val lanes: List<ResourceFairnessLane>,
) {
    init {
        require(revision > 0L)
        require(lanes.map { it.domain }.distinct().size == lanes.size)
    }

    /**
     * Deterministic anti-starvation selection. More deferrals win first, then less-served lanes,
     * then stable enum order.
     */
    fun nextEligible(allowed: Set<ResourceBudgetDomain>): ResourceBudgetDomain? =
        lanes.asSequence()
            .filter { it.domain in allowed && it.queuedOperations > 0L }
            .sortedWith(
                compareByDescending<ResourceFairnessLane> { it.consecutiveDeferrals }
                    .thenBy { it.servedOperations }
                    .thenBy { it.domain.name }
            )
            .firstOrNull()
            ?.domain
}

sealed interface AuthoritativeExecutionState {
    /** Work is durably known not to have produced an effect and the reservation may be released. */
    data class NotStarted(val stateId: String) : AuthoritativeExecutionState {
        init { require(stateId.isNotBlank()) }
    }

    /** Work may still produce an effect; keep the reservation held. */
    data class InFlight(val stateId: String) : AuthoritativeExecutionState {
        init { require(stateId.isNotBlank()) }
    }

    /** A durable outcome/checkpoint exists; settlement can safely consume measured usage. */
    data class Completed(
        val stateId: String,
        val actualUsage: ResourceBudgetUsage,
    ) : AuthoritativeExecutionState {
        init { require(stateId.isNotBlank()) }
    }

    /** Authoritative cancellation/blocked state exists before productive effect. */
    data class Released(val stateId: String, val reason: String) : AuthoritativeExecutionState {
        init {
            require(stateId.isNotBlank())
            require(reason.isNotBlank())
        }
    }

    /** Ambiguous/unreadable state never grants completion and never silently releases capacity. */
    data class Unreadable(val reason: String) : AuthoritativeExecutionState {
        init { require(reason.isNotBlank()) }
    }
}

fun interface AuthoritativeExecutionStateReader {
    suspend fun stateFor(
        accountId: ResourceBudgetAccountId,
        reservation: ResourceBudgetReservation,
    ): AuthoritativeExecutionState
}

enum class ResourceReconciliationAction {
    KEPT_IN_FLIGHT,
    COMMITTED,
    RELEASED,
    BLOCKED_UNREADABLE,
    ALREADY_COMMITTED,
    ALREADY_RELEASED,
}

data class ResourceReservationReconciliation(
    val reservationId: ResourceBudgetReservationId,
    val action: ResourceReconciliationAction,
    val authoritativeStateId: String? = null,
    val detail: String? = null,
)

data class ResourceReservationReconciliationReport(
    val accountId: ResourceBudgetAccountId,
    val entries: List<ResourceReservationReconciliation>,
) {
    val blocked: Boolean
        get() = entries.any { it.action == ResourceReconciliationAction.BLOCKED_UNREADABLE }
}

/**
 * V16 restart reconciler. Settlement is derived only from durable source-of-truth execution state.
 * Ambiguous state keeps capacity held so a restart cannot create budget or duplicate an effect.
 */
class ResourceReservationReconciler(
    private val budgets: ResourceBudgetCoordinator,
    private val executionState: AuthoritativeExecutionStateReader,
) {
    suspend fun reconcile(accountId: ResourceBudgetAccountId): ResourceReservationReconciliationReport {
        val account = budgets.current(accountId)
        val results = account.reservations.map { reservation ->
            when (reservation.state) {
                ResourceBudgetReservationState.COMMITTED ->
                    ResourceReservationReconciliation(
                        reservation.id,
                        ResourceReconciliationAction.ALREADY_COMMITTED,
                    )

                ResourceBudgetReservationState.RELEASED ->
                    ResourceReservationReconciliation(
                        reservation.id,
                        ResourceReconciliationAction.ALREADY_RELEASED,
                    )

                ResourceBudgetReservationState.RESERVED -> reconcileReserved(accountId, reservation)
            }
        }
        return ResourceReservationReconciliationReport(accountId, results)
    }

    private suspend fun reconcileReserved(
        accountId: ResourceBudgetAccountId,
        reservation: ResourceBudgetReservation,
    ): ResourceReservationReconciliation = when (
        val state = executionState.stateFor(accountId, reservation)
    ) {
        is AuthoritativeExecutionState.NotStarted -> {
            budgets.release(accountId, reservation.id)
            ResourceReservationReconciliation(
                reservation.id,
                ResourceReconciliationAction.RELEASED,
                state.stateId,
                "authoritative-not-started",
            )
        }

        is AuthoritativeExecutionState.Released -> {
            budgets.release(accountId, reservation.id)
            ResourceReservationReconciliation(
                reservation.id,
                ResourceReconciliationAction.RELEASED,
                state.stateId,
                state.reason,
            )
        }

        is AuthoritativeExecutionState.InFlight ->
            ResourceReservationReconciliation(
                reservation.id,
                ResourceReconciliationAction.KEPT_IN_FLIGHT,
                state.stateId,
            )

        is AuthoritativeExecutionState.Completed -> {
            budgets.commit(accountId, reservation.id, state.actualUsage)
            ResourceReservationReconciliation(
                reservation.id,
                ResourceReconciliationAction.COMMITTED,
                state.stateId,
            )
        }

        is AuthoritativeExecutionState.Unreadable ->
            ResourceReservationReconciliation(
                reservation.id,
                ResourceReconciliationAction.BLOCKED_UNREADABLE,
                detail = state.reason,
            )
    }
}

private fun ResourceBudgetUsage.cappedBy(quota: ResourceBudgetQuota): ResourceBudgetUsage =
    ResourceBudgetUsage(
        elapsedMillis = minOf(elapsedMillis, quota.elapsedMillis),
        workUnits = minOf(workUnits, quota.workUnits),
        memoryBytes = minOf(memoryBytes, quota.memoryBytes),
        ioBytes = minOf(ioBytes, quota.ioBytes),
        networkBytes = minOf(networkBytes, quota.networkBytes),
        candidates = minOf(candidates, quota.candidates),
    )
