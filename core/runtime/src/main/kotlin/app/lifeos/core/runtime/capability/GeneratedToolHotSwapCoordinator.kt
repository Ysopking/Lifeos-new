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
import app.lifeos.core.runtime.resource.ResourceBudgetQuota
import app.lifeos.core.runtime.resource.ResourceBudgetReservation
import app.lifeos.core.runtime.resource.ResourceBudgetReservationResult
import app.lifeos.core.runtime.resource.ResourceBudgetReservationState
import app.lifeos.core.runtime.resource.ResourceBudgetUsage
import app.lifeos.core.runtime.resource.SharedResourceBudgetDecision
import app.lifeos.core.runtime.resource.SharedResourceBudgetGate
import app.lifeos.core.runtime.resource.SharedResourceBudgetRuntimeRegistry

data class HotSwapResourceProfile(
    val hardQuota: ResourceBudgetQuota,
    val requested: ResourceBudgetUsage,
    val goalRelevance: Double = 1.0,
    val priority: Double = 1.0,
    val expectedUtility: Double = 0.95,
    val confidence: Double = 1.0,
) {
    init {
        require(!requested.isZero())
        require(requested.networkBytes == 0L) {
            "V10 local hot-swap cannot request network access"
        }
        require(goalRelevance in 0.0..1.0)
        require(priority in 0.0..1.0)
        require(expectedUtility in 0.0..1.0)
        require(confidence in 0.0..1.0)
    }
}

sealed interface GeneratedToolHotSwapResult {
    data class Committed(
        val transaction: HotSwapSnapshot,
        val mutation: GeneratedProviderHotSwapMutation,
    ) : GeneratedToolHotSwapResult

    data class Blocked(
        val transaction: HotSwapSnapshot,
        val reason: String,
    ) : GeneratedToolHotSwapResult {
        init { require(reason.isNotBlank()) }
    }

    data class AlreadyTerminal(val transaction: HotSwapSnapshot) : GeneratedToolHotSwapResult
}

class GeneratedToolHotSwapCoordinator(
    private val ledger: HotSwapLedger,
    private val tools: GeneratedToolRegistry,
    private val lifecycle: GeneratedToolLifecycleCoordinator,
    private val capabilities: CapabilityRegistry,
    private val ownerPolicy: OwnerPolicyLedger,
    private val budgets: ResourceBudgetCoordinator,
    private val actorId: OwnerActorId,
    private val ownerScope: String,
    private val sharedBudgets: () -> SharedResourceBudgetGate? =
        { SharedResourceBudgetRuntimeRegistry.current() },
) {
    init { require(ownerScope.isNotBlank()) }

    suspend fun swap(
        previousToolId: String,
        candidateToolId: String,
        evidence: GeneratedToolPromotionEvidence,
        resources: HotSwapResourceProfile,
    ): GeneratedToolHotSwapResult {
        require(previousToolId.isNotBlank() && candidateToolId.isNotBlank())
        require(previousToolId != candidateToolId)
        require(evidence.toolId == candidateToolId)

        val previous = requireNotNull(tools.get(previousToolId)) { "Unknown previous generated tool $previousToolId" }
        val candidate = requireNotNull(tools.get(candidateToolId)) { "Unknown candidate generated tool $candidateToolId" }
        require(previous.state == GeneratedToolState.ACTIVE) { "Previous hot-swap tool must be ACTIVE" }
        require(!previous.promotionEvidenceId.isNullOrBlank()) { "Previous tool has no accepted promotion evidence" }
        require(candidate.manifest.sourceCapability == previous.manifest.sourceCapability) {
            "Hot-swap candidate must implement the same capability"
        }
        require(candidate.state == GeneratedToolState.TRIAL || candidate.state == GeneratedToolState.ACTIVE) {
            "Hot-swap candidate must be TRIAL or staged ACTIVE"
        }
        if (candidate.state == GeneratedToolState.ACTIVE) {
            require(candidate.promotionEvidenceId == evidence.id) {
                "Staged hot-swap candidate was promoted with different evidence"
            }
        }

        var transaction = ledger.prepare(
            capabilityId = previous.manifest.sourceCapability,
            previousToolId = previousToolId,
            candidateToolId = candidateToolId,
            previousPromotionEvidenceId = requireNotNull(previous.promotionEvidenceId),
            candidatePromotionEvidenceId = evidence.id,
        )
        if (transaction.terminal || transaction.state == HotSwapState.REVERT_PREPARED) {
            return GeneratedToolHotSwapResult.AlreadyTerminal(transaction)
        }

        val gate = sharedBudgets()
            ?: return blockWithoutMutation(transaction, "shared-world-budget-gate-not-installed")
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
            ?: return blockWithoutMutation(
                transaction,
                "hot-swap-world-budget:${(allocationDecision as SharedResourceBudgetDecision.Blocked).reason}",
            )
        val allocation = ready.allocation.allocation(ResourceBudgetDomain.HOT_SWAP)
            ?: return blockWithoutMutation(transaction, "hot-swap-world-budget-missing-allocation")
        if (!resources.requested.isWithin(allocation.allocated)) {
            return blockWithoutMutation(transaction, "hot-swap-world-budget-insufficient")
        }

        val accountId = ResourceBudgetAccountId("hot-swap:${transaction.transactionId.value}")
        budgets.createAccount(accountId, resources.hardQuota)
        val reservation = when (
            val result = budgets.reserve(
                accountId = accountId,
                idempotencyKey = transaction.transactionId.value,
                usage = resources.requested,
            )
        ) {
            is ResourceBudgetReservationResult.Denied ->
                return blockWithoutMutation(transaction, result.reason)
            is ResourceBudgetReservationResult.Reserved -> result.reservation
            is ResourceBudgetReservationResult.Existing -> when (result.reservation.state) {
                ResourceBudgetReservationState.RESERVED -> result.reservation
                ResourceBudgetReservationState.COMMITTED -> {
                    require(transaction.state == HotSwapState.COMMITTED) {
                        "Hot-swap budget committed before transaction commit"
                    }
                    return GeneratedToolHotSwapResult.AlreadyTerminal(transaction)
                }
                ResourceBudgetReservationState.RELEASED ->
                    return blockWithoutMutation(transaction, "hot-swap-resource-reservation-released")
            }
        }

        val initialOwner = ownerPolicy.evaluate(
            ownerRequest(previous, candidate, accountId, reservation)
        )
        if (initialOwner is OwnerPolicyDecision.Blocked) {
            budgets.release(accountId, reservation.id)
            return blockWithoutMutation(
                transaction,
                "owner-policy:${initialOwner.reasons.joinToString("|")}",
            )
        }
        initialOwner as OwnerPolicyDecision.Allowed

        if (transaction.state == HotSwapState.PREPARED) {
            val currentCandidate = requireNotNull(tools.get(candidateToolId))
            if (currentCandidate.state == GeneratedToolState.TRIAL) {
                lifecycle.promote(
                    toolId = candidateToolId,
                    evidence = evidence,
                    activationEvidenceRef = evidence.id,
                    actorId = actorId.value,
                    registerCapability = false,
                )
            } else {
                require(currentCandidate.state == GeneratedToolState.ACTIVE)
                require(currentCandidate.promotionEvidenceId == evidence.id)
            }
            transaction = ledger.markCandidatePromoted(
                transaction,
                ownerPolicyRevision = initialOwner.policyRevision,
                worldSnapshotId = ready.allocation.worldSnapshotId,
            )
        }

        val finalCandidate = requireNotNull(tools.get(candidateToolId))
        val finalRequest = ownerRequest(previous, finalCandidate, accountId, reservation)
        val finalOwner = ownerPolicy.evaluate(finalRequest)
        if (finalOwner is OwnerPolicyDecision.Blocked) {
            budgets.release(accountId, reservation.id)
            transaction = ledger.markRolledBack(
                transaction,
                "owner-policy-revoked:${finalOwner.reasons.joinToString("|")}",
            )
            return GeneratedToolHotSwapResult.Blocked(transaction, transaction.lastDetail!!)
        }

        val descriptor = lifecycle.activeDescriptor(candidateToolId)
        val exposure = try {
            OwnerPolicyEffectGate(ownerPolicy).expose(
                request = finalRequest,
            ) {
                val mutation = capabilities.hotSwapGenerated(
                    previousProviderId = previousToolId,
                    candidateDescriptor = descriptor,
                    candidateRecord = finalCandidate,
                    evidence = evidence,
                )
                try {
                    HotSwapCommitExposure(
                        mutation = mutation,
                        transaction = ledger.markCommitted(transaction),
                    )
                } catch (error: Exception) {
                    // Compensation belongs to the same freshly authorized exposure. It never creates
                    // a new productive route; it restores the route that existed before this cutover.
                    capabilities.rollbackHotSwap(
                        capabilityId = previous.manifest.sourceCapability,
                        previousProviderId = previousToolId,
                        candidateProviderId = candidateToolId,
                    )
                    throw error
                }
            }
        } catch (error: Exception) {
            budgets.release(accountId, reservation.id)
            transaction = ledger.markRolledBack(
                transaction,
                "cutover-failed:${error::class.simpleName}:${error.message.orEmpty().take(160)}",
            )
            return GeneratedToolHotSwapResult.Blocked(transaction, transaction.lastDetail!!)
        }

        return when (exposure) {
            is OwnerEffectExposureResult.Blocked -> {
                budgets.release(accountId, reservation.id)
                transaction = ledger.markRolledBack(
                    transaction,
                    "owner-policy-revoked:${exposure.assessment.reasonCodes.joinToString("|") { it.name }}",
                )
                GeneratedToolHotSwapResult.Blocked(transaction, transaction.lastDetail!!)
            }

            is OwnerEffectExposureResult.Exposed -> {
                transaction = exposure.value.transaction
                settleCommittedBudget(accountId, reservation, resources.requested)
                GeneratedToolHotSwapResult.Committed(
                    transaction = transaction,
                    mutation = exposure.value.mutation,
                )
            }
        }
    }

    private fun ownerRequest(
        previous: GeneratedToolRecord,
        candidate: GeneratedToolRecord,
        accountId: ResourceBudgetAccountId,
        reservation: ResourceBudgetReservation,
    ): OwnerEffectRequest = OwnerEffectRequest(
        actorId = actorId,
        effect = OwnerEffectType.PROVIDER_ACTIVATION,
        resource = "hot-swap:${previous.manifest.sourceCapability.value}:${previous.manifest.toolId}->${candidate.manifest.toolId}",
        scope = ownerScope,
        capabilityId = candidate.manifest.sourceCapability,
        providerVersion = candidate.manifest.buildHash,
        budgetAccountId = accountId,
        budgetReservationId = reservation.id,
    )

    private suspend fun blockWithoutMutation(
        transaction: HotSwapSnapshot,
        reason: String,
    ): GeneratedToolHotSwapResult.Blocked {
        val blocked = ledger.markBlocked(transaction, reason)
        return GeneratedToolHotSwapResult.Blocked(blocked, reason)
    }

    private suspend fun settleCommittedBudget(
        accountId: ResourceBudgetAccountId,
        reservation: ResourceBudgetReservation,
        usage: ResourceBudgetUsage,
    ) {
        when (reservation.state) {
            ResourceBudgetReservationState.RESERVED -> budgets.commit(accountId, reservation.id, usage)
            ResourceBudgetReservationState.COMMITTED -> require(reservation.settledUsage == usage)
            ResourceBudgetReservationState.RELEASED -> error("Committed hot-swap has released resource reservation")
        }
    }

    private data class HotSwapCommitExposure(
        val mutation: GeneratedProviderHotSwapMutation,
        val transaction: HotSwapSnapshot,
    )
}
