package app.lifeos.next.kernel

import app.lifeos.core.model.StableCognitiveIds
import app.lifeos.core.runtime.life.FutureEvidenceScenario
import app.lifeos.core.runtime.life.FuturePlanningAdmissibility
import app.lifeos.core.runtime.life.FuturePlanningAuthority
import app.lifeos.core.runtime.life.FutureScenarioType
import app.lifeos.core.runtime.policy.OwnerPolicyLedger
import app.lifeos.core.runtime.resource.ResourceBudgetDemand
import app.lifeos.core.runtime.resource.ResourceBudgetDomain
import app.lifeos.core.runtime.resource.ResourceBudgetQuota
import app.lifeos.core.runtime.resource.ResourceBudgetUsage
import app.lifeos.core.runtime.resource.SharedResourceBudgetDecision
import app.lifeos.core.runtime.resource.SharedResourceBudgetGate
import kotlin.math.roundToLong

/**
 * Private-APK K authority. Planning itself has no host effect, so it does not manufacture a new
 * OwnerPolicy grant. Instead every decision is bound to the live fail-closed owner ledger snapshot;
 * any eventual goal effect still has to pass PrivateGoalActionExecutionGuard immediately before
 * execution. Actionable planning candidates additionally need a live World Formula allocation.
 */
class PrivateFuturePlanningAuthority(
    private val ownerPolicy: OwnerPolicyLedger,
    private val resources: SharedResourceBudgetGate,
) : FuturePlanningAuthority {
    override suspend fun assess(scenario: FutureEvidenceScenario): FuturePlanningAdmissibility {
        val policy = ownerPolicy.snapshot()
        val policyFingerprint = StableCognitiveIds.fingerprint(
            "future-planning-owner-policy/v1",
            policy.revision.toString(),
            *policy.activeGrants.map { it.id.value }.sorted().toTypedArray(),
        )
        if (!scenario.allowed) {
            return FuturePlanningAdmissibility(
                allowed = false,
                policyRevision = policy.revision,
                policyFingerprint = policyFingerprint,
                resourceFingerprint = StableCognitiveIds.fingerprint(
                    "future-planning-observation/v1",
                    scenario.id,
                    policyFingerprint,
                ),
                reason = "observation-only",
            )
        }

        val requested = requestedUsage(scenario)
        val demand = ResourceBudgetDemand(
            domain = ResourceBudgetDomain.COGNITION,
            requested = requested,
            goalRelevance = scenario.probability,
            priority = priority(scenario.type),
            expectedUtility = scenario.probability,
            confidence = scenario.probability,
        )
        return when (val decision = resources.allocate(HARD_QUOTA, listOf(demand))) {
            is SharedResourceBudgetDecision.Blocked -> FuturePlanningAdmissibility(
                allowed = false,
                policyRevision = policy.revision,
                policyFingerprint = policyFingerprint,
                resourceFingerprint = StableCognitiveIds.fingerprint(
                    "future-planning-resource-block/v1",
                    scenario.id,
                    demand.fingerprint(),
                    decision.reason,
                ),
                reason = decision.reason,
            )
            is SharedResourceBudgetDecision.Ready -> {
                val allocation = requireNotNull(decision.allocation.allocation(ResourceBudgetDomain.COGNITION)) {
                    "Future planning allocation omitted cognition domain"
                }
                val allowed = requested.isWithin(allocation.allocated)
                FuturePlanningAdmissibility(
                    allowed = allowed,
                    policyRevision = policy.revision,
                    policyFingerprint = policyFingerprint,
                    resourceFingerprint = StableCognitiveIds.fingerprint(
                        "future-planning-resource-ready/v1",
                        scenario.id,
                        decision.allocation.worldSnapshotId,
                        decision.allocation.hardwareSnapshotFingerprint,
                        allocation.demandFingerprint,
                        allocation.allocated.elapsedMillis.toString(),
                        allocation.allocated.workUnits.toString(),
                        allocation.allocated.memoryBytes.toString(),
                        allocation.allocated.ioBytes.toString(),
                        allocation.allocated.networkBytes.toString(),
                        allocation.allocated.candidates.toString(),
                    ),
                    reason = if (allowed) "planning-resource-admissible" else "planning-resource-allocation-insufficient",
                )
            }
        }
    }

    private fun requestedUsage(scenario: FutureEvidenceScenario): ResourceBudgetUsage {
        val cost = scenario.resourceCost.coerceIn(0.0, 1.0)
        return ResourceBudgetUsage(
            elapsedMillis = 300L + (1_200.0 * cost).roundToLong(),
            workUnits = 1L + (7.0 * cost).roundToLong(),
            memoryBytes = (4L + (12.0 * cost).roundToLong()) * MIB,
            ioBytes = (1.0 * cost).roundToLong() * MIB,
            networkBytes = 0L,
            candidates = 1L,
        )
    }

    private fun priority(type: FutureScenarioType): Double = when (type) {
        FutureScenarioType.PREEMPTIVE_ACTION -> 0.80
        FutureScenarioType.KNOWLEDGE_ACQUISITION -> 0.65
        FutureScenarioType.OPPORTUNITY_ACTION -> 0.60
        FutureScenarioType.INACTION_PRESSURE -> 0.25
    }

    private companion object {
        const val MIB = 1024L * 1024L
        val HARD_QUOTA = ResourceBudgetQuota(
            elapsedMillis = 3_000L,
            workUnits = 16L,
            memoryBytes = 32L * MIB,
            ioBytes = 4L * MIB,
            networkBytes = 0L,
            candidates = 4L,
        )
    }
}
