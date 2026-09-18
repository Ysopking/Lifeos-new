package app.lifeos.core.life

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import java.time.Duration
import java.time.Instant

data class LegalEvidence(
    val photonId: PhotonId,
    val revision: Long,
    val verification: VerificationState,
    val effectiveAt: Instant? = null,
    val claimId: String = "${photonId.value}@$revision",
) {
    init {
        require(revision > 0L)
        require(claimId.isNotBlank())
    }

    val photonRef: PhotonRevisionRef get() = PhotonRevisionRef(photonId, revision)
}

data class ClaimEvidenceGroup(
    val claimId: String,
    val evidence: List<PhotonRevisionRef>,
    val effectiveAt: Instant?,
) {
    init {
        require(claimId.isNotBlank())
        require(evidence.isNotEmpty())
    }
}

data class ClaimVerificationResult(
    val claimId: String,
    val state: VerificationState,
    val group: ClaimEvidenceGroup,
    val conflictReasons: Set<String> = emptySet(),
    val staleReasons: Set<String> = emptySet(),
) {
    init {
        require(claimId.isNotBlank())
        require(conflictReasons.none { it.isBlank() })
        require(staleReasons.none { it.isBlank() })
    }
}

data class LegalEvidenceResult(
    val evidence: List<LegalEvidence>,
    val state: VerificationState,
    val unresolvedReasons: Set<String> = emptySet(),
    val claims: List<ClaimVerificationResult> = emptyList(),
)

class LegalEvidencePipeline {
    fun evaluate(evidence: List<LegalEvidence>): LegalEvidenceResult {
        if (evidence.isEmpty()) {
            return LegalEvidenceResult(
                evidence = emptyList(),
                state = VerificationState.UNRESOLVED,
                unresolvedReasons = setOf("missing-evidence"),
            )
        }

        val claims = evidence.groupBy { it.claimId }
            .toSortedMap()
            .map { (claimId, values) ->
                val ordered = values.sortedWith(
                    compareBy<LegalEvidence> { it.effectiveAt ?: Instant.MIN }
                        .thenBy { it.photonId.value }
                        .thenBy { it.revision }
                )
                val effectiveAt = ordered.mapNotNull { it.effectiveAt }.maxOrNull()
                val states = ordered.mapTo(linkedSetOf()) { it.verification }
                val conflictReasons = buildSet {
                    if (VerificationState.CONFLICTED in states) add("explicit-conflict")
                    if (
                        VerificationState.VERIFIED in states &&
                        VerificationState.CONFLICTED in states
                    ) add("verified-and-conflicted-evidence")
                }
                val staleReasons = buildSet {
                    if (VerificationState.OUTDATED in states) add("outdated-evidence-present")
                    val latestEffective = ordered.lastOrNull()?.effectiveAt
                    if (latestEffective != null && effectiveAt != null && latestEffective < effectiveAt) {
                        add("newer-effective-evidence-exists")
                    }
                }
                val state = when {
                    conflictReasons.isNotEmpty() -> VerificationState.CONFLICTED
                    states == setOf(VerificationState.OUTDATED) -> VerificationState.OUTDATED
                    VerificationState.VERIFIED in states && staleReasons.isEmpty() -> VerificationState.VERIFIED
                    VerificationState.VERIFIED in states -> VerificationState.OUTDATED
                    VerificationState.EXTRACTED in states -> VerificationState.EXTRACTED
                    else -> VerificationState.UNRESOLVED
                }
                ClaimVerificationResult(
                    claimId = claimId,
                    state = state,
                    group = ClaimEvidenceGroup(
                        claimId = claimId,
                        evidence = ordered.map { it.photonRef },
                        effectiveAt = effectiveAt,
                    ),
                    conflictReasons = conflictReasons,
                    staleReasons = staleReasons,
                )
            }

        val aggregate = when {
            claims.any { it.state == VerificationState.CONFLICTED } -> VerificationState.CONFLICTED
            claims.any { it.state == VerificationState.UNRESOLVED } -> VerificationState.UNRESOLVED
            claims.any { it.state == VerificationState.OUTDATED } -> VerificationState.OUTDATED
            claims.all { it.state == VerificationState.VERIFIED } -> VerificationState.VERIFIED
            else -> VerificationState.EXTRACTED
        }
        val unresolved = buildSet {
            claims.filter { it.state == VerificationState.UNRESOLVED }
                .forEach { add("unresolved-claim:${it.claimId}") }
            claims.filter { it.conflictReasons.isNotEmpty() }
                .forEach { claim ->
                    claim.conflictReasons.forEach { add("claim:${claim.claimId}:conflict:$it") }
                }
            claims.filter { it.staleReasons.isNotEmpty() }
                .forEach { claim ->
                    claim.staleReasons.forEach { add("claim:${claim.claimId}:stale:$it") }
                }
        }
        return LegalEvidenceResult(
            evidence = evidence,
            state = aggregate,
            unresolvedReasons = unresolved,
            claims = claims,
        )
    }
}

data class ReconciliationCandidate(
    val estimatedOutstanding: MoneyAmount?,
    val conflicted: Boolean,
    val reasons: Set<String>,
)

class FinancialReconciliationEngine {
    fun reconcile(debt: DebtMatter): ReconciliationCandidate {
        val amounts = buildList {
            debt.claimedPrincipal?.let(::add)
            debt.claimedFees?.let(::add)
            debt.claimedInterest?.let(::add)
            debt.verifiedAmount?.let(::add)
            debt.disputedAmount?.let(::add)
            debt.payments.forEach { add(it.amount) }
        }
        val currencies = amounts.mapTo(linkedSetOf()) { it.currency }
        if (currencies.size > 1) {
            return ReconciliationCandidate(null, true, setOf("currency-conflict"))
        }
        if (amounts.any { it.minorUnits < 0L }) {
            return ReconciliationCandidate(null, true, setOf("negative-money-component"))
        }

        val paymentById = linkedMapOf<String, DebtPayment>()
        val duplicateReasons = linkedSetOf<String>()
        debt.payments.forEach { payment ->
            val previous = paymentById[payment.id]
            when {
                previous == null -> paymentById[payment.id] = payment
                previous == payment -> duplicateReasons += "duplicate-payment-id:${payment.id}"
                else -> duplicateReasons += "conflicting-payment-id:${payment.id}"
            }
        }
        if (duplicateReasons.any { it.startsWith("conflicting-") }) {
            return ReconciliationCandidate(null, true, duplicateReasons)
        }

        val currency = amounts.firstOrNull()?.currency ?: "EUR"
        val gross = try {
            when {
                debt.verifiedAmount != null -> when (requireNotNull(debt.verifiedAmountBasis)) {
                    VerifiedDebtAmountBasis.PRINCIPAL_ONLY -> {
                        Math.addExact(
                            Math.addExact(
                                debt.verifiedAmount.minorUnits,
                                debt.claimedFees?.minorUnits ?: 0L,
                            ),
                            debt.claimedInterest?.minorUnits ?: 0L,
                        )
                    }
                    VerifiedDebtAmountBasis.TOTAL_CLAIM -> debt.verifiedAmount.minorUnits
                    VerifiedDebtAmountBasis.COMPONENT_SUM -> debt.verifiedAmount.minorUnits
                }
                debt.claimedPrincipal != null -> Math.addExact(
                    Math.addExact(
                        debt.claimedPrincipal.minorUnits,
                        debt.claimedFees?.minorUnits ?: 0L,
                    ),
                    debt.claimedInterest?.minorUnits ?: 0L,
                )
                else -> return ReconciliationCandidate(
                    null,
                    true,
                    setOf("missing-base-amount"),
                )
            }
        } catch (_: ArithmeticException) {
            return ReconciliationCandidate(null, true, setOf("amount-overflow"))
        }

        val paid = try {
            paymentById.values.fold(0L) { total, payment ->
                Math.addExact(total, payment.amount.minorUnits)
            }
        } catch (_: ArithmeticException) {
            return ReconciliationCandidate(null, true, setOf("payment-overflow"))
        }

        val outstanding = try {
            Math.subtractExact(gross, paid)
        } catch (_: ArithmeticException) {
            return ReconciliationCandidate(null, true, setOf("outstanding-overflow"))
        }
        if (outstanding < 0L) {
            return ReconciliationCandidate(
                estimatedOutstanding = null,
                conflicted = true,
                reasons = duplicateReasons + "payments-exceed-claim",
            )
        }

        val reasons = buildSet {
            addAll(duplicateReasons)
            if (debt.verifiedAmount == null) add("amount-not-verified")
            if (debt.disputedAmount != null) add("disputed-amount-present")
            debt.verifiedAmountBasis?.let { add("verified-basis:${it.name}") }
        }
        return ReconciliationCandidate(
            estimatedOutstanding = MoneyAmount.normalized(outstanding, currency),
            conflicted = debt.disputedAmount != null ||
                duplicateReasons.any { it.startsWith("conflicting-") },
            reasons = reasons,
        )
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
            when {
                hours < 0 -> 1_000_000L
                hours <= 24 -> 900_000L
                hours <= 168 -> 650_000L
                else -> 200_000L
            }
        } ?: 0L
        val score = (
            (deadline * 35L + input.consequenceMicros * 30L + input.noveltyMicros * 10L) / 75L +
                if (input.unresolvedConflict) 250_000L else 0L
            ).coerceIn(0L, 1_000_000L)
        val kind = when {
            input.unresolvedConflict ||
                input.verification == VerificationState.CONFLICTED -> LifeAttentionKind.CONFLICT
            input.verification != VerificationState.VERIFIED -> LifeAttentionKind.VERIFY
            input.actionable && deadline >= 650_000L -> LifeAttentionKind.ACTION_REQUIRED
            input.dueAt != null -> LifeAttentionKind.UPCOMING
            input.actionable -> LifeAttentionKind.ACTION_REQUIRED
            else -> LifeAttentionKind.INFORMATIONAL
        }
        return LifeAttention(
            input.id,
            input.matterId,
            kind,
            score,
            setOf("deadline:$deadline", "verification:${input.verification}"),
        )
    }
}
