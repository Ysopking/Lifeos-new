package app.lifeos.core.runtime.evolution

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.policy.OwnerActorId
import app.lifeos.core.runtime.policy.OwnerEffectRequest
import app.lifeos.core.runtime.policy.OwnerEffectType
import app.lifeos.core.runtime.policy.OwnerPolicyDecision
import app.lifeos.core.runtime.policy.OwnerPolicyLedger
import app.lifeos.core.runtime.resource.ResourceBudgetAccountId
import app.lifeos.core.runtime.resource.ResourceBudgetCoordinator
import app.lifeos.core.runtime.resource.ResourceBudgetQuota
import app.lifeos.core.runtime.resource.ResourceBudgetReservation
import app.lifeos.core.runtime.resource.ResourceBudgetReservationResult
import app.lifeos.core.runtime.resource.ResourceBudgetReservationState
import app.lifeos.core.runtime.resource.ResourceBudgetUsage

/** One bounded candidate execution observation that becomes durable J07 evidence. */
data class ControlledEvolutionCandidateObservation(
    val success: Boolean,
    val producedExpectedOutput: Boolean,
    val outputFingerprint: String? = null,
    val latencyMs: Long,
    val hardFailures: Set<EvolutionHardFailure> = emptySet(),
) {
    init {
        require(latencyMs >= 0L)
        require(outputFingerprint == null || outputFingerprint.isNotBlank())
        require(!success || hardFailures.isEmpty())
        if (producedExpectedOutput) require(!outputFingerprint.isNullOrBlank())
    }
}

data class ControlledEvolutionCandidatePermit(
    val toolId: String,
    val adoptionEvidenceId: String,
    val invocationId: String,
    val canaryReservation: EvolutionCanaryReservation,
    val resourceReservation: ResourceBudgetReservation,
    val ownerPolicyRevision: Long,
) {
    init {
        require(toolId.isNotBlank())
        require(adoptionEvidenceId.isNotBlank())
        require(invocationId.isNotBlank())
        require(resourceReservation.state == ResourceBudgetReservationState.RESERVED)
    }
}

fun interface ControlledEvolutionOutcomeRecorder {
    suspend fun record(
        evidence: EvolutionCanaryEvidenceBundle,
        input: EvolutionCanaryOutcomeInput,
    ): EvolutionCanaryOutcomeRecordResult
}

sealed interface ControlledEvolutionExecutionResult {
    data class Baseline(val route: EvolutionCanaryRoute.Baseline) : ControlledEvolutionExecutionResult
    data class Completed(
        val outcome: EvolutionCanaryOutcome,
        val recovered: Boolean,
    ) : ControlledEvolutionExecutionResult

    data class Blocked(val reason: String) : ControlledEvolutionExecutionResult {
        init { require(reason.isNotBlank()) }
    }
}

/**
 * V8 control boundary for replacement canaries.
 *
 * Order is deliberate and restart-safe:
 * 1. initialize/verify the immutable hard V16 account when a process-owned quota is configured;
 * 2. recover a previously persisted outcome before any candidate callback can run again;
 * 3. reserve shared V16 resources under a stable idempotency key;
 * 4. re-read V14 owner policy;
 * 5. let the existing trusted canary router replay J05 and reserve its bounded invocation;
 * 6. re-read owner policy immediately before candidate execution;
 * 7. persist J07 outcome/kill-switch evidence;
 * 8. settle the shared resource reservation only after durable outcome evidence exists.
 *
 * A crash after J07 persistence but before V16 settlement is repaired by the recovery path and
 * cannot cause the candidate invocation to execute twice.
 */
class ControlledEvolutionCoordinator(
    private val ownerPolicy: OwnerPolicyLedger,
    private val budgets: ResourceBudgetCoordinator,
    private val router: EvolutionCanaryRouter,
    private val outcomeRecorder: ControlledEvolutionOutcomeRecorder,
    private val budgetAccountId: ResourceBudgetAccountId,
    private val actorId: OwnerActorId,
    private val ownerScope: String,
    private val accountQuota: ResourceBudgetQuota? = null,
) {
    init { require(ownerScope.isNotBlank()) }

    suspend fun execute(
        evidence: EvolutionCanaryEvidenceBundle,
        context: EvolutionCanaryRoutingContext,
        reservedUsage: ResourceBudgetUsage,
        candidate: suspend (ControlledEvolutionCandidatePermit) -> ControlledEvolutionCandidateObservation,
    ): ControlledEvolutionExecutionResult {
        require(!reservedUsage.isZero()) { "Controlled evolution requires an explicit non-zero resource reservation" }
        require(reservedUsage.networkBytes == 0L) {
            "Initial controlled-evolution canaries must not reserve network access"
        }
        accountQuota?.let { quota -> budgets.createAccount(budgetAccountId, quota) }

        val idempotencyKey = resourceIdempotencyKey(evidence, context)
        val existingOutcome = router.durableOutcome(evidence.adoptionEvidence.id, context.invocationId)
        if (existingOutcome != null) {
            settleRecoveredBudget(idempotencyKey, reservedUsage)
            return ControlledEvolutionExecutionResult.Completed(existingOutcome, recovered = true)
        }

        val resourceReservation = when (
            val reservation = budgets.reserve(
                accountId = budgetAccountId,
                idempotencyKey = idempotencyKey,
                usage = reservedUsage,
            )
        ) {
            is ResourceBudgetReservationResult.Denied ->
                return ControlledEvolutionExecutionResult.Blocked(reservation.reason)
            is ResourceBudgetReservationResult.Existing -> when (reservation.reservation.state) {
                ResourceBudgetReservationState.RESERVED -> reservation.reservation
                ResourceBudgetReservationState.COMMITTED -> error(
                    "Evolution resource budget is committed without durable outcome evidence"
                )
                ResourceBudgetReservationState.RELEASED ->
                    return ControlledEvolutionExecutionResult.Blocked("evolution-resource-reservation-released")
            }
            is ResourceBudgetReservationResult.Reserved -> reservation.reservation
        }

        val firstOwnerDecision = ownerDecision(evidence, resourceReservation)
        if (firstOwnerDecision is OwnerPolicyDecision.Blocked) {
            budgets.release(budgetAccountId, resourceReservation.id)
            return ControlledEvolutionExecutionResult.Blocked(
                "owner-policy:${firstOwnerDecision.reasons.joinToString("|")}",
            )
        }

        val route = router.route(evidence, context)
        if (route is EvolutionCanaryRoute.Baseline) {
            budgets.release(budgetAccountId, resourceReservation.id)
            return ControlledEvolutionExecutionResult.Baseline(route)
        }
        route as EvolutionCanaryRoute.Candidate

        val finalOwnerDecision = ownerDecision(evidence, resourceReservation)
        if (finalOwnerDecision is OwnerPolicyDecision.Blocked) {
            // The J06 canary reservation intentionally remains visible. It may never be hidden from
            // readiness accounting merely because authority was revoked after routing.
            budgets.release(budgetAccountId, resourceReservation.id)
            return ControlledEvolutionExecutionResult.Blocked(
                "owner-policy-revoked:${finalOwnerDecision.reasons.joinToString("|")}",
            )
        }
        finalOwnerDecision as OwnerPolicyDecision.Allowed

        val observation = candidate(
            ControlledEvolutionCandidatePermit(
                toolId = route.toolId,
                adoptionEvidenceId = route.adoptionEvidenceId,
                invocationId = context.invocationId,
                canaryReservation = route.reservation,
                resourceReservation = resourceReservation,
                ownerPolicyRevision = finalOwnerDecision.policyRevision,
            )
        )
        val recorded = outcomeRecorder.record(
            evidence,
            EvolutionCanaryOutcomeInput(
                reservationId = route.reservation.id,
                invocationId = context.invocationId,
                success = observation.success,
                producedExpectedOutput = observation.producedExpectedOutput,
                outputFingerprint = observation.outputFingerprint,
                latencyMs = observation.latencyMs,
                hardFailures = observation.hardFailures,
            ),
        )

        // Charge the full reserved envelope. This is intentionally conservative and makes crash
        // recovery deterministic without adding a second usage ledger to J07 evidence.
        budgets.commit(
            accountId = budgetAccountId,
            reservationId = resourceReservation.id,
            actualUsage = reservedUsage,
        )
        return ControlledEvolutionExecutionResult.Completed(recorded.outcome, recovered = false)
    }

    private suspend fun settleRecoveredBudget(
        idempotencyKey: String,
        reservedUsage: ResourceBudgetUsage,
    ) {
        val account = budgets.current(budgetAccountId)
        val reservation = requireNotNull(
            account.reservations.firstOrNull { it.idempotencyKey == idempotencyKey }
        ) { "Durable evolution outcome exists without its V16 reservation" }
        require(reservation.reserved == reservedUsage) {
            "Recovered evolution reservation was requested with different resource usage"
        }
        when (reservation.state) {
            ResourceBudgetReservationState.RESERVED -> budgets.commit(
                budgetAccountId,
                reservation.id,
                reservedUsage,
            )
            ResourceBudgetReservationState.COMMITTED -> require(reservation.settledUsage == reservedUsage) {
                "Recovered evolution reservation has different settled usage"
            }
            ResourceBudgetReservationState.RELEASED -> error(
                "Durable evolution outcome cannot belong to a released V16 reservation"
            )
        }
    }

    private suspend fun ownerDecision(
        evidence: EvolutionCanaryEvidenceBundle,
        reservation: ResourceBudgetReservation,
    ): OwnerPolicyDecision = ownerPolicy.evaluate(
        OwnerEffectRequest(
            actorId = actorId,
            effect = OwnerEffectType.TOOL_EXECUTION,
            resource = "evolution-canary:${evidence.currentCandidate.manifest.toolId}",
            scope = ownerScope,
            capabilityId = evidence.currentCandidate.manifest.sourceCapability,
            providerVersion = evidence.currentCandidate.manifest.buildHash,
            budgetAccountId = budgetAccountId,
            budgetReservationId = reservation.id,
        )
    )

    private fun resourceIdempotencyKey(
        evidence: EvolutionCanaryEvidenceBundle,
        context: EvolutionCanaryRoutingContext,
    ): String = "evolution-canary:" + StableFieldIds.fingerprint(
        "controlled-evolution-resource/v1",
        evidence.adoptionEvidence.id,
        evidence.currentCandidate.manifest.toolId,
        evidence.currentCandidate.evolutionFingerprint(),
        context.invocationId,
    )
}
