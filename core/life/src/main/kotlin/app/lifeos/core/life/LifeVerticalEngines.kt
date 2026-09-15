package app.lifeos.core.life

import app.lifeos.core.model.PhotonId
import java.time.Duration
import java.time.Instant

data class LegalEvidence(
    val photonId: PhotonId,
    val revision: Long,
    val verification: VerificationState,
    val effectiveAt: Instant? = null,
)

data class LegalEvidenceResult(
    val evidence: List<LegalEvidence>,
    val state: VerificationState,
    val unresolvedReasons: Set<String> = emptySet(),
)

class LegalEvidencePipeline {
    fun evaluate(evidence: List<LegalEvidence>): LegalEvidenceResult {
        val state = when {
            evidence.isEmpty() -> VerificationState.UNRESOLVED
            evidence.any { it.verification == VerificationState.CONFLICTED } -> VerificationState.CONFLICTED
            evidence.any { it.verification == VerificationState.OUTDATED } && evidence.none { it.verification == VerificationState.VERIFIED } -> VerificationState.OUTDATED
            evidence.any { it.verification == VerificationState.VERIFIED } -> VerificationState.VERIFIED
            else -> VerificationState.EXTRACTED
        }
        return LegalEvidenceResult(evidence, state, if (evidence.isEmpty()) setOf("missing-evidence") else emptySet())
    }
}

data class ReconciliationCandidate(
    val estimatedOutstanding: MoneyAmount?,
    val conflicted: Boolean,
    val reasons: Set<String>,
)

class FinancialReconciliationEngine {
    fun reconcile(debt: DebtMatter): ReconciliationCandidate {
        val currencies = buildSet {
            debt.claimedPrincipal?.let { add(it.currency) }; debt.claimedFees?.let { add(it.currency) }
            debt.claimedInterest?.let { add(it.currency) }; debt.verifiedAmount?.let { add(it.currency) }
            debt.payments.forEach { add(it.amount.currency) }
        }
        if (currencies.size > 1) return ReconciliationCandidate(null, true, setOf("currency-conflict"))
        val base = debt.verifiedAmount ?: debt.claimedPrincipal ?: return ReconciliationCandidate(null, true, setOf("missing-base-amount"))
        val paid = debt.payments.sumOf { it.amount.minorUnits }
        val fees = debt.claimedFees?.minorUnits ?: 0L
        val interest = debt.claimedInterest?.minorUnits ?: 0L
        val outstanding = (base.minorUnits + fees + interest - paid).coerceAtLeast(0L)
        val reasons = buildSet {
            if (debt.verifiedAmount == null) add("amount-not-verified")
            if (debt.disputedAmount != null) add("disputed-amount-present")
        }
        return ReconciliationCandidate(MoneyAmount(outstanding, base.currency), debt.disputedAmount != null, reasons)
    }
}

class CrossDomainPlanner {
    fun plan(tasks: Collection<DailyPlanTask>): List<DailyPlanTask> = tasks.sortedWith(
        compareBy<DailyPlanTask> { it.completed }
            .thenBy { it.priority.ordinal }
            .thenBy { it.latestAt ?: Instant.MAX }
            .thenBy { it.id }
    )
}

data class AttentionInput(
    val id: String,
    val matterId: LifeMatterId?,
    val dueAt: Instant?,
    val consequenceMicros: Long,
    val verification: VerificationState,
    val actionable: Boolean,
    val unresolvedConflict: Boolean,
    val noveltyMicros: Long,
)

class LifeAttentionEngine {
    fun evaluate(input: AttentionInput, now: Instant): LifeAttention {
        val deadline = input.dueAt?.let { due ->
            val hours = Duration.between(now, due).toHours()
            when { hours < 0 -> 1_000_000L; hours <= 24 -> 900_000L; hours <= 168 -> 650_000L; else -> 200_000L }
        } ?: 0L
        val score = ((deadline * 35L + input.consequenceMicros * 30L + input.noveltyMicros * 10L) / 75L +
            if (input.unresolvedConflict) 250_000L else 0L).coerceIn(0L, 1_000_000L)
        val kind = when {
            input.unresolvedConflict || input.verification == VerificationState.CONFLICTED -> LifeAttentionKind.CONFLICT
            input.verification !in setOf(VerificationState.VERIFIED) -> LifeAttentionKind.VERIFY
            input.actionable && deadline >= 650_000L -> LifeAttentionKind.ACTION_REQUIRED
            input.dueAt != null -> LifeAttentionKind.UPCOMING
            input.actionable -> LifeAttentionKind.ACTION_REQUIRED
            else -> LifeAttentionKind.INFORMATIONAL
        }
        return LifeAttention(input.id, input.matterId, kind, score, setOf("deadline:$deadline", "verification:${input.verification}"))
    }
}
