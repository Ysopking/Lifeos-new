package app.lifeos.core.runtime.health

import app.lifeos.core.runtime.resource.ResourceBudgetAccountId
import app.lifeos.core.runtime.resource.ResourceBudgetCoordinator
import app.lifeos.core.runtime.resource.ResourceBudgetDemand
import app.lifeos.core.runtime.resource.ResourceBudgetDomain
import app.lifeos.core.runtime.resource.ResourceBudgetQuota
import app.lifeos.core.runtime.resource.ResourceBudgetReservation
import app.lifeos.core.runtime.resource.ResourceBudgetReservationResult
import app.lifeos.core.runtime.resource.ResourceBudgetReservationState
import app.lifeos.core.runtime.resource.ResourceBudgetUsage
import app.lifeos.core.runtime.resource.SharedResourceBudgetDecision
import app.lifeos.core.runtime.resource.SharedResourceBudgetGate
import app.lifeos.core.runtime.resource.SharedResourceBudgetRuntimeRegistry
import java.time.Instant
import kotlinx.coroutines.CancellationException

/** Hard per-incident ceiling plus one action's requested working set. */
data class SelfHealingResourceProfile(
    val hardQuota: ResourceBudgetQuota,
    val perActionRequested: ResourceBudgetUsage,
    val goalRelevance: Double = 1.0,
    val priority: Double = 1.0,
    val expectedUtility: Double = 1.0,
    val confidence: Double = 1.0,
) {
    init {
        require(!perActionRequested.isZero())
        require(perActionRequested.networkBytes == 0L) {
            "V9 self-healing actions are local-only unless a future explicit external-effect gate is added"
        }
        require(goalRelevance in 0.0..1.0)
        require(priority in 0.0..1.0)
        require(expectedUtility in 0.0..1.0)
        require(confidence in 0.0..1.0)
    }
}

sealed interface DurableSelfHealingResult {
    data class Recovered(
        val incident: SelfHealingIncidentSnapshot,
        val evidence: CompositeRepairEvidence,
        val recoveredAfterRestart: Boolean,
    ) : DurableSelfHealingResult

    data class Exhausted(
        val incident: SelfHealingIncidentSnapshot,
        val quarantined: Boolean,
    ) : DurableSelfHealingResult

    data class Blocked(
        val incident: SelfHealingIncidentSnapshot,
        val reason: String,
    ) : DurableSelfHealingResult {
        init { require(reason.isNotBlank()) }
    }
}

/**
 * V9 durable self-healing boundary.
 *
 * The action is marked in-flight before execution. If the process dies after a repair side effect,
 * restart never blindly re-runs that action: verification probes run first. Healthy evidence closes
 * the incident; non-healthy evidence marks the interrupted action consumed and advances to the next
 * registered repair. Every fresh repair action must receive a persisted World Formula SELF_HEALING
 * allocation and a durable V16 reservation before it can execute.
 */
class DurableSelfHealingCoordinator(
    private val ledger: SelfHealingLedger,
    private val healthGraph: HealthGraph,
    private val quarantineRegistry: QuarantineRegistry,
    private val budgets: ResourceBudgetCoordinator,
    private val sharedBudgetProvider: () -> SharedResourceBudgetGate? =
        { SharedResourceBudgetRuntimeRegistry.current() },
    private val now: () -> Instant = Instant::now,
) {
    suspend fun recover(
        plan: RecoveryPlan,
        incidentFingerprint: String,
        resources: SelfHealingResourceProfile,
    ): DurableSelfHealingResult {
        var incident = ledger.open(plan, incidentFingerprint)
        terminalResult(incident)?.let { return it }

        val accountId = ResourceBudgetAccountId("self-healing:${incident.incidentId.value}")
        budgets.createAccount(accountId, resources.hardQuota)

        healthGraph.record(
            HealthObservation(
                nodeId = plan.nodeId,
                state = HealthState.RECOVERING,
                observedAt = now(),
                source = plan.source,
                message = "durable-self-healing-started",
            )
        )

        if (incident.state == SelfHealingIncidentState.ACTION_IN_FLIGHT) {
            val evidence = CompositeRepairProbe(plan.verificationProbes, now).collect(plan.nodeId)
            settleExistingReservation(accountId, incident, resources.perActionRequested)
            if (evidence.verifiedHealthy) {
                quarantineRegistry.release(plan.nodeId)
                healthGraph.recordHealthy(
                    id = plan.nodeId,
                    source = "${plan.source}:restart-verification",
                    message = "recovery-verified-after-restart",
                    observedAt = evidence.capturedAt,
                )
                incident = ledger.markRecovered(
                    incident,
                    detail = "restart-verification-recovered",
                    evidenceSummary = evidence.summary(),
                )
                return DurableSelfHealingResult.Recovered(
                    incident = incident,
                    evidence = evidence,
                    recoveredAfterRestart = true,
                )
            }
            incident = ledger.markInterrupted(
                incident,
                detail = "in-flight-action-not-verified-after-restart",
                evidenceSummary = evidence.summary(),
            )
        }

        while (incident.nextActionIndex < plan.actions.size) {
            val actionIndex = incident.nextActionIndex
            val action = plan.actions[actionIndex]
            val budgetDecision = allocate(resources)
            val ready = budgetDecision as? SharedResourceBudgetDecision.Ready
                ?: return block(
                    incident,
                    "self-healing-world-budget:${(budgetDecision as SharedResourceBudgetDecision.Blocked).reason}",
                )
            val allocation = ready.allocation.allocation(ResourceBudgetDomain.SELF_HEALING)
                ?: return block(incident, "self-healing-world-budget-missing-allocation")
            if (!resources.perActionRequested.isWithin(allocation.allocated)) {
                return block(incident, "self-healing-world-budget-insufficient")
            }

            val reservation = reserve(accountId, incident, actionIndex, action.id, resources.perActionRequested)
                ?: return block(incident, "self-healing-v16-budget-exhausted")
            incident = ledger.markPrepared(incident, actionIndex, action.id)

            val actionResult = try {
                action.execute()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                RecoveryActionResult.Failure(
                    message = "repair-exception:${error::class.simpleName}:${error.message.orEmpty().take(160)}",
                    retryable = true,
                )
            }

            when (actionResult) {
                is RecoveryActionResult.Failure -> {
                    settle(reservation, accountId, resources.perActionRequested)
                    incident = ledger.markActionFailed(incident, actionResult.message)
                    if (!actionResult.retryable) {
                        return exhaust(plan, incident, "non-retryable:${actionResult.message}")
                    }
                }
                is RecoveryActionResult.Success -> {
                    val evidence = CompositeRepairProbe(plan.verificationProbes, now).collect(plan.nodeId)
                    settle(reservation, accountId, resources.perActionRequested)
                    if (evidence.verifiedHealthy) {
                        quarantineRegistry.release(plan.nodeId)
                        healthGraph.recordHealthy(
                            id = plan.nodeId,
                            source = "${plan.source}:${action.id}",
                            message = actionResult.message ?: "recovery-verified",
                            observedAt = evidence.capturedAt,
                        )
                        incident = ledger.markRecovered(
                            incident,
                            detail = actionResult.message ?: "recovery-verified",
                            evidenceSummary = evidence.summary(),
                        )
                        return DurableSelfHealingResult.Recovered(
                            incident = incident,
                            evidence = evidence,
                            recoveredAfterRestart = false,
                        )
                    }
                    incident = ledger.markVerificationFailed(
                        incident,
                        detail = "verification-failed:${evidence.summary()}",
                        evidenceSummary = evidence.summary(),
                    )
                }
            }
        }

        return exhaust(plan, incident, incident.lastDetail ?: "recovery-exhausted")
    }

    private suspend fun allocate(resources: SelfHealingResourceProfile): SharedResourceBudgetDecision {
        val gate = sharedBudgetProvider()
            ?: return SharedResourceBudgetDecision.Blocked("shared-world-budget-gate-not-installed")
        return gate.allocate(
            hardQuota = resources.hardQuota,
            demands = listOf(
                ResourceBudgetDemand(
                    domain = ResourceBudgetDomain.SELF_HEALING,
                    requested = resources.perActionRequested,
                    goalRelevance = resources.goalRelevance,
                    priority = resources.priority,
                    expectedUtility = resources.expectedUtility,
                    confidence = resources.confidence,
                )
            ),
        )
    }

    private suspend fun reserve(
        accountId: ResourceBudgetAccountId,
        incident: SelfHealingIncidentSnapshot,
        actionIndex: Int,
        actionId: String,
        usage: ResourceBudgetUsage,
    ): ResourceBudgetReservation? {
        val key = reservationKey(incident, actionIndex, actionId)
        return when (val result = budgets.reserve(accountId, key, usage)) {
            is ResourceBudgetReservationResult.Denied -> null
            is ResourceBudgetReservationResult.Reserved -> result.reservation
            is ResourceBudgetReservationResult.Existing -> when (result.reservation.state) {
                ResourceBudgetReservationState.RESERVED,
                ResourceBudgetReservationState.COMMITTED -> result.reservation
                ResourceBudgetReservationState.RELEASED -> null
            }
        }
    }

    private suspend fun settle(
        reservation: ResourceBudgetReservation,
        accountId: ResourceBudgetAccountId,
        usage: ResourceBudgetUsage,
    ) {
        when (reservation.state) {
            ResourceBudgetReservationState.RESERVED -> budgets.commit(accountId, reservation.id, usage)
            ResourceBudgetReservationState.COMMITTED -> require(reservation.settledUsage == usage)
            ResourceBudgetReservationState.RELEASED -> error("Self-healing reservation was released after action exposure")
        }
    }

    private suspend fun settleExistingReservation(
        accountId: ResourceBudgetAccountId,
        incident: SelfHealingIncidentSnapshot,
        usage: ResourceBudgetUsage,
    ) {
        val actionIndex = requireNotNull(incident.inFlightActionIndex)
        val actionId = requireNotNull(incident.inFlightActionId)
        val key = reservationKey(incident, actionIndex, actionId)
        val account = budgets.current(accountId)
        val reservation = requireNotNull(account.reservations.firstOrNull { it.idempotencyKey == key }) {
            "In-flight self-healing action has no durable V16 reservation"
        }
        require(reservation.reserved == usage) {
            "Restarted self-healing request changed its resource envelope"
        }
        settle(reservation, accountId, usage)
    }

    private suspend fun block(
        incident: SelfHealingIncidentSnapshot,
        reason: String,
    ): DurableSelfHealingResult.Blocked {
        val blocked = ledger.markBlocked(incident, reason)
        return DurableSelfHealingResult.Blocked(blocked, reason)
    }

    private suspend fun exhaust(
        plan: RecoveryPlan,
        incident: SelfHealingIncidentSnapshot,
        reason: String,
    ): DurableSelfHealingResult.Exhausted {
        healthGraph.record(
            HealthObservation(
                nodeId = plan.nodeId,
                state = HealthState.UNHEALTHY,
                observedAt = now(),
                source = plan.source,
                message = reason,
            )
        )
        var terminal = ledger.markExhausted(incident, reason)
        var quarantined = false
        if (plan.quarantineOnFailure) {
            val at = now()
            quarantineRegistry.quarantine(
                QuarantineEntry(
                    nodeId = plan.nodeId,
                    source = plan.source,
                    reason = reason,
                    quarantinedAt = at,
                )
            )
            healthGraph.record(
                HealthObservation(
                    nodeId = plan.nodeId,
                    state = HealthState.QUARANTINED,
                    observedAt = at,
                    source = plan.source,
                    message = reason,
                )
            )
            terminal = ledger.markQuarantined(terminal, reason)
            quarantined = true
        }
        return DurableSelfHealingResult.Exhausted(terminal, quarantined)
    }

    private fun terminalResult(snapshot: SelfHealingIncidentSnapshot): DurableSelfHealingResult? = when (snapshot.state) {
        SelfHealingIncidentState.BLOCKED -> DurableSelfHealingResult.Blocked(
            snapshot,
            snapshot.lastDetail ?: "self-healing-blocked",
        )
        SelfHealingIncidentState.EXHAUSTED,
        SelfHealingIncidentState.QUARANTINED -> DurableSelfHealingResult.Exhausted(
            snapshot,
            snapshot.state == SelfHealingIncidentState.QUARANTINED,
        )
        SelfHealingIncidentState.RECOVERED -> null // Recovery evidence is not reconstructed from summary alone.
        SelfHealingIncidentState.OPEN,
        SelfHealingIncidentState.ACTION_IN_FLIGHT -> null
    }

    private fun reservationKey(
        incident: SelfHealingIncidentSnapshot,
        actionIndex: Int,
        actionId: String,
    ): String = "self-healing:${incident.incidentId.value}:$actionIndex:$actionId"
}
