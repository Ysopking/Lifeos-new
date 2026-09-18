package app.lifeos.core.runtime.escalation

import java.time.Instant

data class EscalationPolicyConfig(
    val quarantineAfterConsecutiveFailures: Int = 3,
    val fallbackAfterConsecutiveFailures: Int = 2,
) {
    init {
        require(quarantineAfterConsecutiveFailures > 0)
        require(fallbackAfterConsecutiveFailures > 0)
        require(fallbackAfterConsecutiveFailures <= quarantineAfterConsecutiveFailures) {
            "Fallback threshold must not exceed quarantine threshold"
        }
    }
}

/**
 * Central deterministic L0-L7 policy. It selects one level only; execution belongs to
 * EscalationCoordinator and existing subsystem authorities.
 */
class EscalationPolicy(
    private val config: EscalationPolicyConfig = EscalationPolicyConfig(),
) {
    fun decide(
        trigger: EscalationTrigger,
        decidedAt: Instant = trigger.observedAt,
    ): EscalationDecision {
        val (level, reasons) = select(trigger)
        return EscalationDecision(
            escalationId = trigger.id,
            triggerFingerprint = trigger.fingerprint,
            level = level,
            reasonCodes = reasons.distinct().sorted(),
            decidedAt = decidedAt,
        )
    }

    private fun select(trigger: EscalationTrigger): Pair<EscalationLevel, List<String>> {
        if (trigger.protectionCritical) {
            return EscalationLevel.L6_SAFE_MODE to listOf(
                "protection-critical-integrity-failure",
                "scope:" + trigger.scope.name.lowercase(),
            )
        }

        if (
            trigger.recentPromotionId != null &&
            trigger.rollbackAvailable &&
            (trigger.consecutiveFailures > 0 || !trigger.recoverable)
        ) {
            return EscalationLevel.L5_ROLLBACK to listOf(
                "recent-promotion-regression",
                "promotion:" + trigger.recentPromotionId,
            )
        }

        if (trigger.retryBudgetRemaining && trigger.recoverable) {
            return EscalationLevel.L0_RETRY to listOf(
                "recoverable-failure",
                "retry-budget-available",
            )
        }

        if (trigger.contextInconsistent) {
            return EscalationLevel.L1_REEVALUATE to listOf(
                "context-inconsistent",
                "retry-not-selected",
            )
        }

        val lowerMitigationObserved = trigger.priorLevels.any {
            it in setOf(
                EscalationLevel.L2_RECOVER_COMPONENT,
                EscalationLevel.L3_QUARANTINE,
                EscalationLevel.L4_FALLBACK,
                EscalationLevel.L5_ROLLBACK,
                EscalationLevel.L6_SAFE_MODE,
            )
        }
        if (
            trigger.repairProposalEligible &&
            trigger.missingCapabilityId != null &&
            lowerMitigationObserved
        ) {
            return EscalationLevel.L7_REPAIR_PROPOSAL to listOf(
                "demonstrated-missing-or-defective-capability",
                "lower-mitigation-evidence-present",
                "capability:" + trigger.missingCapabilityId,
            )
        }

        if (
            trigger.knownGoodFallbackId != null &&
            trigger.consecutiveFailures >= config.fallbackAfterConsecutiveFailures
        ) {
            return EscalationLevel.L4_FALLBACK to listOf(
                "known-good-fallback-available",
                "fallback:" + trigger.knownGoodFallbackId,
                "failure-threshold:" + config.fallbackAfterConsecutiveFailures,
            )
        }

        if (trigger.consecutiveFailures >= config.quarantineAfterConsecutiveFailures) {
            return EscalationLevel.L3_QUARANTINE to listOf(
                "component-failure-threshold-exhausted",
                "failure-threshold:" + config.quarantineAfterConsecutiveFailures,
            )
        }

        if (trigger.recoverable) {
            return EscalationLevel.L2_RECOVER_COMPONENT to listOf(
                "component-local-recoverable-failure",
                "retry-budget-exhausted",
            )
        }

        if (trigger.missingCapabilityId != null) {
            return EscalationLevel.L1_REEVALUATE to listOf(
                "missing-capability-without-repair-evidence",
                "capability:" + trigger.missingCapabilityId,
            )
        }

        return EscalationLevel.L3_QUARANTINE to listOf(
            "non-recoverable-component-failure",
            "category:" + trigger.category.name.lowercase(),
        )
    }
}
