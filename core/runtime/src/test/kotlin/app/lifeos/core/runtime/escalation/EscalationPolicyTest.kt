package app.lifeos.core.runtime.escalation

import app.lifeos.core.runtime.health.HealthFailureCategory
import app.lifeos.core.runtime.health.HealthNodeId
import app.lifeos.core.runtime.health.HealthScope
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EscalationPolicyTest {
    private val at = Instant.parse("2026-09-19T00:00:00Z")
    private val policy = EscalationPolicy()

    @Test
    fun transientFailureStaysAtRetryWhileBudgetRemains() {
        assertEquals(
            EscalationLevel.L0_RETRY,
            policy.decide(trigger(retryBudgetRemaining = true)).level,
        )
    }

    @Test
    fun inconsistentContextReevaluatesWithoutQuarantine() {
        assertEquals(
            EscalationLevel.L1_REEVALUATE,
            policy.decide(
                trigger(
                    retryBudgetRemaining = false,
                    contextInconsistent = true,
                )
            ).level,
        )
    }

    @Test
    fun recoverableWorkerFailureUsesComponentRecoveryAfterRetryExhaustion() {
        assertEquals(
            EscalationLevel.L2_RECOVER_COMPONENT,
            policy.decide(
                trigger(
                    scope = HealthScope.WORKER,
                    retryBudgetRemaining = false,
                    consecutiveFailures = 1,
                )
            ).level,
        )
    }

    @Test
    fun repeatedFieldFailureQuarantinesAtThreshold() {
        assertEquals(
            EscalationLevel.L3_QUARANTINE,
            policy.decide(
                trigger(
                    scope = HealthScope.FIELD,
                    retryBudgetRemaining = false,
                    consecutiveFailures = 3,
                )
            ).level,
        )
    }

    @Test
    fun knownGoodFallbackPrecedesQuarantine() {
        val decision = policy.decide(
            trigger(
                category = HealthFailureCategory.RESOURCE,
                retryBudgetRemaining = false,
                consecutiveFailures = 2,
                knownGoodFallbackId = "cpu-reference",
            )
        )
        assertEquals(EscalationLevel.L4_FALLBACK, decision.level)
        assertTrue(decision.reasonCodes.any { it == "fallback:cpu-reference" })
    }

    @Test
    fun recentPromotionRegressionPrefersRollback() {
        assertEquals(
            EscalationLevel.L5_ROLLBACK,
            policy.decide(
                trigger(
                    retryBudgetRemaining = false,
                    consecutiveFailures = 1,
                    recentPromotionId = "promotion-17",
                    rollbackAvailable = true,
                )
            ).level,
        )
    }

    @Test
    fun protectionCriticalFailureJumpsDirectlyToSafeMode() {
        assertEquals(
            EscalationLevel.L6_SAFE_MODE,
            policy.decide(
                trigger(
                    category = HealthFailureCategory.DATA_CORRUPTION,
                    recoverable = false,
                    retryBudgetRemaining = false,
                    protectionCritical = true,
                )
            ).level,
        )
    }

    @Test
    fun repairProposalRequiresPriorMitigationEvidence() {
        val withoutEvidence = policy.decide(
            trigger(
                recoverable = false,
                retryBudgetRemaining = false,
                missingCapabilityId = "capability.missing",
                repairProposalEligible = true,
            )
        )
        assertNotEquals(EscalationLevel.L7_REPAIR_PROPOSAL, withoutEvidence.level)

        val withEvidence = policy.decide(
            trigger(
                recoverable = false,
                retryBudgetRemaining = false,
                missingCapabilityId = "capability.missing",
                repairProposalEligible = true,
                priorLevels = listOf(EscalationLevel.L2_RECOVER_COMPONENT),
            )
        )
        assertEquals(EscalationLevel.L7_REPAIR_PROPOSAL, withEvidence.level)
    }

    @Test
    fun nonRecoverableComponentFailureQuarantinesWhenNoSaferRouteExists() {
        assertEquals(
            EscalationLevel.L3_QUARANTINE,
            policy.decide(
                trigger(
                    recoverable = false,
                    retryBudgetRemaining = false,
                )
            ).level,
        )
    }

    @Test
    fun sameTriggerAndTimeProduceSameDecisionFingerprint() {
        val trigger = trigger(
            retryBudgetRemaining = false,
            contextInconsistent = true,
            evidenceRefs = setOf("evidence-b", "evidence-a"),
        )
        val first = policy.decide(trigger, at)
        val second = policy.decide(trigger, at)
        assertEquals(first, second)
        assertEquals(first.fingerprint, second.fingerprint)
        assertEquals(first.reasonCodes.sorted(), first.reasonCodes)
    }

    private fun trigger(
        scope: HealthScope = HealthScope.RUNTIME,
        category: HealthFailureCategory = HealthFailureCategory.TRANSIENT,
        recoverable: Boolean = true,
        consecutiveFailures: Int = 1,
        retryBudgetRemaining: Boolean = false,
        contextInconsistent: Boolean = false,
        knownGoodFallbackId: String? = null,
        recentPromotionId: String? = null,
        rollbackAvailable: Boolean = false,
        protectionCritical: Boolean = false,
        missingCapabilityId: String? = null,
        repairProposalEligible: Boolean = false,
        priorLevels: List<EscalationLevel> = emptyList(),
        evidenceRefs: Set<String> = emptySet(),
    ) = EscalationTrigger(
        nodeId = HealthNodeId("node-1"),
        scope = scope,
        category = category,
        recoverable = recoverable,
        consecutiveFailures = consecutiveFailures,
        retryBudgetRemaining = retryBudgetRemaining,
        contextInconsistent = contextInconsistent,
        knownGoodFallbackId = knownGoodFallbackId,
        recentPromotionId = recentPromotionId,
        rollbackAvailable = rollbackAvailable,
        protectionCritical = protectionCritical,
        missingCapabilityId = missingCapabilityId,
        repairProposalEligible = repairProposalEligible,
        priorLevels = priorLevels,
        evidenceRefs = evidenceRefs,
        observedAt = at,
    )
}
