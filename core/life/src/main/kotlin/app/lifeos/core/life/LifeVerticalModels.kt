package app.lifeos.core.life

import app.lifeos.core.model.PhotonId
import java.time.Instant

@JvmInline value class LifeMatterId(val value: String)

enum class VerificationState { EXTRACTED, VERIFIED, CONFLICTED, OUTDATED, UNRESOLVED }

data class AuthoritiesMatter(
    val matterId: LifeMatterId,
    val authority: String,
    val caseReference: String? = null,
    val documentPhotonIds: Set<PhotonId> = emptySet(),
    val claimPhotonIds: Set<PhotonId> = emptySet(),
    val requirementIds: Set<String> = emptySet(),
    val deadlineIds: Set<String> = emptySet(),
    val submittedEvidencePhotonIds: Set<PhotonId> = emptySet(),
    val legalReferencePhotonIds: Set<PhotonId> = emptySet(),
    val openQuestions: Set<String> = emptySet(),
    val proposedActions: Set<String> = emptySet(),
)

data class LifeDeadline(
    val id: String,
    val matterId: LifeMatterId,
    val sourcePhotonId: PhotonId,
    val sourceRevision: Long,
    val candidateDueAt: Instant?,
    val verification: VerificationState,
    val confidenceMicros: Long,
) {
    init { require(sourceRevision > 0); require(confidenceMicros in 0..1_000_000) }
}

data class MoneyAmount(val minorUnits: Long, val currency: String = "EUR") {
    init { require(currency.isNotBlank()) }
}

data class DebtPayment(val id: String, val amount: MoneyAmount, val paidAt: Instant, val sourcePhotonId: PhotonId?)

data class DebtMatter(
    val matterId: LifeMatterId,
    val creditor: String,
    val caseReference: String? = null,
    val claimedPrincipal: MoneyAmount? = null,
    val claimedFees: MoneyAmount? = null,
    val claimedInterest: MoneyAmount? = null,
    val verifiedAmount: MoneyAmount? = null,
    val disputedAmount: MoneyAmount? = null,
    val payments: List<DebtPayment> = emptyList(),
    val outstandingEstimate: MoneyAmount? = null,
    val installmentDueAt: Instant? = null,
)

data class DebtLedgerRow(
    val matterId: LifeMatterId,
    val creditor: String,
    val claimed: MoneyAmount?,
    val verified: MoneyAmount?,
    val disputed: MoneyAmount?,
    val paid: MoneyAmount,
    val estimatedOutstanding: MoneyAmount?,
    val nextDueAt: Instant?,
    val lastChangedAt: Instant,
)

data class DebtLedgerProjection(val revision: Long, val rows: List<DebtLedgerRow>, val fingerprint: String)

enum class DailyPriority { HARD_DEADLINE, APPOINTMENT, REQUIRED, IMPORTANT, FLEXIBLE, OPTIONAL }

data class DailyPlanTask(
    val id: String,
    val title: String,
    val priority: DailyPriority,
    val estimatedMinutes: Int,
    val earliestAt: Instant? = null,
    val latestAt: Instant? = null,
    val dependencyIds: Set<String> = emptySet(),
    val matterId: LifeMatterId? = null,
    val context: String? = null,
    val completed: Boolean = false,
) { init { require(title.isNotBlank()); require(estimatedMinutes >= 0) } }

enum class LifeAttentionKind { ACTION_REQUIRED, VERIFY, UPCOMING, WAITING, CONFLICT, INFORMATIONAL }

data class LifeAttention(
    val id: String,
    val matterId: LifeMatterId?,
    val kind: LifeAttentionKind,
    val scoreMicros: Long,
    val reasons: Set<String>,
) { init { require(scoreMicros in 0..1_000_000) } }

enum class LifeRelationKind { BELONGS_TO, REQUIRES_ACTION, SATISFIES, CREATES_DEADLINE, AFFECTS_BUDGET, AFFECTS_PLAN, RELATED_TO }

data class LifeRelation(val fromId: String, val toId: String, val kind: LifeRelationKind)
