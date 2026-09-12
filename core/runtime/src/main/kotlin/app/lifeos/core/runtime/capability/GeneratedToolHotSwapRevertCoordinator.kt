package app.lifeos.core.runtime.capability

import app.lifeos.core.runtime.policy.OwnerActorId
import app.lifeos.core.runtime.policy.OwnerEffectExposureResult
import app.lifeos.core.runtime.policy.OwnerEffectRequest
import app.lifeos.core.runtime.policy.OwnerEffectType
import app.lifeos.core.runtime.policy.OwnerPolicyDecision
import app.lifeos.core.runtime.policy.OwnerPolicyEffectGate
import app.lifeos.core.runtime.policy.OwnerPolicyLedger
import app.lifeos.core.runtime.resource.ResourceBudgetAccountId
import app.lifeos.core.runtime.resource.ResourceBudgetCoordinator
import app.lifeos.core.runtime.resource.ResourceBudgetDemand
import app.lifeos.core.runtime.resource.ResourceBudgetDomain
import app.lifeos.core.runtime.resource.ResourceBudgetReservation
import app.lifeos.core.runtime.resource.ResourceBudgetReservationResult
import app.lifeos.core.runtime.resource.ResourceBudgetReservationState
import app.lifeos.core.runtime.resource.SharedResourceBudgetDecision
import app.lifeos.core.runtime.resource.SharedResourceBudgetGate
import app.lifeos.core.runtime.resource.SharedResourceBudgetRuntimeRegistry

sealed interface GeneratedToolHotSwapRevertResult {
    data class Reverted(val transaction: HotSwapSnapshot) : GeneratedToolHotSwapRevertResult
    data class AlreadyReverted(val transaction: HotSwapSnapshot) : GeneratedToolHotSwapRevertResult
    data class Blocked(val transaction: HotSwapSnapshot, val reason: String) : GeneratedToolHotSwapRevertResult {
        init { require(reason.isNotBlank()) }
    }
}

/**
 * Reverts one already committed V10 cutover without mutating either promoted tool lifecycle record.
 * The previous provider remains a promotion-valid standby and is reactivated only through the same
 * owner-policy, World Formula and V16 boundaries used for the forward cutover.
 */
class GeneratedToolHotSwapRevertCoordinator(
    private val ledger: HotSwapLedger,
    private val tools: GeneratedToolRegistry,
    private val capabilities: CapabilityRegistry,
    private val ownerPolicy: OwnerPolicyLedger,
    private val budgets: ResourceBudgetCoordinator,
    private val actorId: OwnerActorId,
    private val ownerScope: String,
    private val sharedBudgets: () -> SharedResourceBudgetGate? =
        { SharedResourceBudgetRuntimeRegistry.current() },
) {
    init { require(ownerScope.isNotBlank()) }

    suspend fun revert(
        transactionId: HotSwapTransactionId,
        resources: HotSwapResourceProfile,
    ): GeneratedToolHotSwapRevertResult {
        var transaction = requireNotNull(ledger.snapshot(transactionId)) {
            "Unknown hot-swap transaction $transactionId"
        }
        if (transaction.state == HotSwapState.REVERTED) {
            return GeneratedToolHotSwapRevertResult.AlreadyReverted(transaction)
        }
        require(transaction.state == HotSwapState.COMMITTED || transaction.state == HotSwapState.REVERT_PREPARED) {
            "Only a committed hot-swap can be reverted"
        }

        val previous = requireNotNull(tools.get(transaction.previousToolId)) {
            "Previous generated tool is missing"
        }
        val candidate = requireNotNull(tools.get(transaction.candidateToolId)) {
            "Candidate generated tool is missing"
        }
        require(previous.state == GeneratedToolState.ACTIVE && candidate.state == GeneratedToolState.ACTIVE) {
            "Hot-swap revert requires both promoted lifecycle records to remain ACTIVE"
        }
        require(previous.promotionEvidenceId == transaction.previousPromotionEvidenceId) {
            "Previous standby promotion evidence changed"
        }
        require(candidate.promotionEvidenceId == transaction.candidatePromotionEvidenceId) {
            "Candidate promotion evidence changed"
        }

        val accountId = ResourceBudgetAccountId("hot-swap-revert:${transaction.transactionId.value}")
        val idempotencyKey = "revert:${transaction.transactionId.value}"
        val reservation = if (transaction.state == HotSwapState.REVERT_PREPARED) {
            existingReservation(accountId, idempotencyKey, resources)
        } else {
            val gate = sharedBudgets()
                ?: return GeneratedToolHotSwapRevertResult.Blocked(
                    transaction,
                    "shared-world-budget-gate-not-installed",
                )
            val allocationDecision = gate.allocate(
                hardQuota = resources.hardQuota,
                demands = listOf(
                    ResourceBudgetDemand(
                        domain = ResourceBudgetDomain.HOT_SWAP,
                        requested = resources.requested,
                        goalRelevance = resources.goalRelevance,
                        priority = resources.priority,
                        expectedUtility = resources.expectedUtility,
                        confidence = resources.confidence,
                    )
                ),
            )
            val ready = allocationDecision as? SharedResourceBudgetDecision.Ready
                ?: return GeneratedToolHotSwapRevertResult.Blocked(
                    transaction,
                    "hot-swap-revert-world-budget:${(allocationDecision as SharedResourceBudgetDecision.Blocked).reason}",
                )
            val allocation = ready.allocation.allocation(ResourceBudgetDomain.HOT_SWAP)
                ?: return GeneratedToolHotSwapRevertResult.Blocked(
                    transaction,
                    "hot-swap-revert-world-budget-missing-allocation",
                )
            if (!resources.requested.isWithin(allocation.allocated)) {
                return GeneratedToolHotSwapRevertResult.Blocked(
                    transaction,
                    "hot-swap-revert-world-budget-insufficient",
                )
            }

            budgets.createAccount(accountId, resources.hardQuota)
            val reserved = reserve(accountId, idempotencyKey, resources)
                ?: return GeneratedToolHotSwapRevertResult.Blocked(
                    transaction,
                    "hot-swap-revert-resource-budget-exhausted",
                )
            val owner = ownerPolicy.evaluate(
                ownerRequest(transaction, previous, accountId, reserved)
            )
            if (owner is OwnerPolicyDecision.Blocked) {
                budgets.release(accountId, reserved.id)
                return GeneratedToolHotSwapRevertResult.Blocked(
                    transaction,
                    "owner-policy:${owner.reasons.joinToString("|")}",
                )
            }
            owner as OwnerPolicyDecision.Allowed
            transaction = ledger.markRevertPrepared(
                transaction,
                ownerPolicyRevision = owner.policyRevision,
                worldSnapshotId = ready.allocation.worldSnapshotId,
            )
            reserved
        }

        when (
            val exposure = OwnerPolicyEffectGate(ownerPolicy).expose(
                request = ownerRequest(transaction, previous, accountId, reservation),
            ) {
                capabilities.rollbackHotSwap(
                    capabilityId = transaction.capabilityId,
                    previousProviderId = transaction.previousToolId,
                    candidateProviderId = transaction.candidateToolId,
                )
            }
        ) {
            is OwnerEffectExposureResult.Blocked -> return GeneratedToolHotSwapRevertResult.Blocked(
                transaction,
                "owner-policy-revoked:${exposure.assessment.reasonCodes.joinToString("|") { it.name }}",
            )
            is OwnerEffectExposureResult.Exposed -> Unit
        }
        transaction = ledger.markReverted(transaction)
        settleCommittedBudget(accountId, reservation)
        return GeneratedToolHotSwapRevertResult.Reverted(transaction)
    }

    private fun ownerRequest(
        transaction: HotSwapSnapshot,
        previous: GeneratedToolRecord,
        accountId: ResourceBudgetAccountId,
        reservation: ResourceBudgetReservation,
    ): OwnerEffectRequest = OwnerEffectRequest(
        actorId = actorId,
        effect = OwnerEffectType.PROVIDER_ACTIVATION,
        resource = "hot-swap:revert:${transaction.capabilityId.value}:${transaction.candidateToolId}->${transaction.previousToolId}",
        scope = ownerScope,
        capabilityId = previous.manifest.sourceCapability,
        providerVersion = previous.manifest.buildHash,
        budgetAccountId = accountId,
        budgetReservationId = reservation.id,
    )

    private suspend fun reserve(
        accountId: ResourceBudgetAccountId,
        idempotencyKey: String,
        resources: HotSwapResourceProfile,
    ): ResourceBudgetReservation? = when (
        val result = budgets.reserve(accountId, idempotencyKey, resources.requested)
    ) {
        is ResourceBudgetReservationResult.Denied -> null
        is ResourceBudgetReservationResult.Reserved -> result.reservation
        is ResourceBudgetReservationResult.Existing -> when (result.reservation.state) {
            ResourceBudgetReservationState.RESERVED -> result.reservation
            ResourceBudgetReservationState.COMMITTED -> error("Revert budget committed before durable revert")
            ResourceBudgetReservationState.RELEASED -> null
        }
    }

    private suspend fun existingReservation(
        accountId: ResourceBudgetAccountId,
        idempotencyKey: String,
        resources: HotSwapResourceProfile,
    ): ResourceBudgetReservation {
        val account = requireNotNull(budgets.currentOrNull(accountId)) {
            "Prepared hot-swap revert has no durable V16 account"
        }
        require(account.quota == resources.hardQuota) {
            "Prepared hot-swap revert changed its hard resource quota"
        }
        val reservation = requireNotNull(account.reservations.singleOrNull { it.idempotencyKey == idempotencyKey }) {
            "Prepared hot-swap revert has no durable V16 reservation"
        }
        require(reservation.reserved == resources.requested) {
            "Prepared hot-swap revert changed its resource request"
        }
        require(reservation.state != ResourceBudgetReservationState.RELEASED) {
            "Prepared hot-swap revert cannot resume a released reservation"
        }
        return reservation
    }

    private suspend fun settleCommittedBudget(
        accountId: ResourceBudgetAccountId,
        reservation: ResourceBudgetReservation,
    ) {
        when (reservation.state) {
            ResourceBudgetReservationState.RESERVED -> budgets.commit(accountId, reservation.id, reservation.reserved)
            ResourceBudgetReservationState.COMMITTED -> require(reservation.settledUsage == reservation.reserved)
            ResourceBudgetReservationState.RELEASED -> error("Reverted hot-swap has released resource reservation")
        }
    }
}
