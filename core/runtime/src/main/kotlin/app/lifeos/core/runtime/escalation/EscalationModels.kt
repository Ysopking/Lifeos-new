package app.lifeos.core.runtime.escalation

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.health.HealthFailureCategory
import app.lifeos.core.runtime.health.HealthNodeId
import app.lifeos.core.runtime.health.HealthScope
import java.time.Instant

enum class EscalationLevel {
    L0_RETRY,
    L1_REEVALUATE,
    L2_RECOVER_COMPONENT,
    L3_QUARANTINE,
    L4_FALLBACK,
    L5_ROLLBACK,
    L6_SAFE_MODE,
    L7_REPAIR_PROPOSAL,
}

@JvmInline
value class EscalationId(val value: String) {
    init {
        require(value.startsWith(PREFIX)) { "Invalid escalation id" }
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}"))) {
            "Invalid escalation id digest"
        }
    }

    companion object {
        const val PREFIX = "escalation:"
    }
}

data class EscalationTrigger(
    val nodeId: HealthNodeId,
    val scope: HealthScope,
    val category: HealthFailureCategory,
    val recoverable: Boolean,
    val consecutiveFailures: Int,
    val retryBudgetRemaining: Boolean,
    val componentRecoveryAvailable: Boolean = true,
    val contextInconsistent: Boolean = false,
    val knownGoodFallbackId: String? = null,
    val recentPromotionId: String? = null,
    val rollbackAvailable: Boolean = false,
    val protectionCritical: Boolean = false,
    val missingCapabilityId: String? = null,
    val repairProposalEligible: Boolean = false,
    val priorLevels: List<EscalationLevel> = emptyList(),
    val evidenceRefs: Set<String> = emptySet(),
    val observedAt: Instant,
) {
    init {
        require(consecutiveFailures >= 0) { "Consecutive failures must not be negative" }
        require(knownGoodFallbackId == null || knownGoodFallbackId.isNotBlank())
        require(recentPromotionId == null || recentPromotionId.isNotBlank())
        require(missingCapabilityId == null || missingCapabilityId.isNotBlank())
        require(evidenceRefs.none { it.isBlank() }) { "Escalation evidence refs must not be blank" }
        require(priorLevels == priorLevels.distinct()) {
            "Escalation prior levels must be unique and ordered by occurrence"
        }
        require(!rollbackAvailable || recentPromotionId != null) {
            "Rollback availability requires recent promotion identity"
        }
        require(!repairProposalEligible || missingCapabilityId != null) {
            "Repair proposal eligibility requires missing capability identity"
        }
    }

    val fingerprint: String = StableFieldIds.fingerprint(
        "escalation-trigger/v1",
        nodeId.value,
        scope.name,
        category.name,
        recoverable.toString(),
        consecutiveFailures.toString(),
        retryBudgetRemaining.toString(),
        componentRecoveryAvailable.toString(),
        contextInconsistent.toString(),
        knownGoodFallbackId.orEmpty(),
        recentPromotionId.orEmpty(),
        rollbackAvailable.toString(),
        protectionCritical.toString(),
        missingCapabilityId.orEmpty(),
        repairProposalEligible.toString(),
        observedAt.toString(),
        *priorLevels.map { "prior:" + it.name }.toTypedArray(),
        *evidenceRefs.sorted().map { "evidence:" + it }.toTypedArray(),
    )

    val id: EscalationId = EscalationId(EscalationId.PREFIX + fingerprint)
}

data class EscalationDecision(
    val escalationId: EscalationId,
    val triggerFingerprint: String,
    val level: EscalationLevel,
    val reasonCodes: List<String>,
    val decidedAt: Instant,
) {
    init {
        require(triggerFingerprint.isNotBlank())
        require(reasonCodes.isNotEmpty() && reasonCodes.none { it.isBlank() })
        require(reasonCodes == reasonCodes.distinct().sorted()) {
            "Escalation reason codes must be unique and deterministic"
        }
    }

    val fingerprint: String = StableFieldIds.fingerprint(
        "escalation-decision/v1",
        escalationId.value,
        triggerFingerprint,
        level.name,
        decidedAt.toString(),
        *reasonCodes.toTypedArray(),
    )
}

enum class EscalationRecordType {
    OPENED,
    DECIDED,
    ACTION_STARTED,
    ACTION_SUCCEEDED,
    ACTION_FAILED,
    BLOCKED,
    CLOSED,
}

data class EscalationRecord(
    val revision: Long,
    val escalationId: EscalationId,
    val nodeId: HealthNodeId,
    val triggerFingerprint: String,
    val type: EscalationRecordType,
    val recordedAt: Instant,
    val level: EscalationLevel? = null,
    val detail: String? = null,
    val evidenceRefs: Set<String> = emptySet(),
) {
    init {
        require(revision > 0L)
        require(triggerFingerprint.isNotBlank())
        require(detail == null || detail.isNotBlank())
        require(evidenceRefs.none { it.isBlank() })
        if (type in setOf(
                EscalationRecordType.DECIDED,
                EscalationRecordType.ACTION_STARTED,
                EscalationRecordType.ACTION_SUCCEEDED,
                EscalationRecordType.ACTION_FAILED,
                EscalationRecordType.BLOCKED,
            )
        ) {
            require(level != null) { "$type requires escalation level" }
        }
    }
}

data class EscalationRepositoryLoadReport(
    val records: List<EscalationRecord>,
    val unreadableEntries: List<String> = emptyList(),
)

interface EscalationRepository {
    suspend fun loadReport(): EscalationRepositoryLoadReport
    suspend fun append(expectedRevision: Long, record: EscalationRecord): Boolean
}

enum class EscalationState {
    OPEN,
    DECIDED,
    ACTION_IN_FLIGHT,
    ACTION_SUCCEEDED,
    ACTION_FAILED,
    BLOCKED,
    CLOSED,
}

data class EscalationSnapshot(
    val escalationId: EscalationId,
    val nodeId: HealthNodeId,
    val triggerFingerprint: String,
    val state: EscalationState,
    val level: EscalationLevel? = null,
    val lastDetail: String? = null,
    val evidenceRefs: Set<String> = emptySet(),
    val ledgerRevision: Long,
    val lastRecordedAt: Instant,
) {
    init {
        require(triggerFingerprint.isNotBlank())
        require(lastDetail == null || lastDetail.isNotBlank())
        require(evidenceRefs.none { it.isBlank() })
        require(ledgerRevision > 0L)
        if (state != EscalationState.OPEN) {
            require(level != null || state == EscalationState.CLOSED) {
                "Non-open escalation state requires a level"
            }
        }
    }

    val terminal: Boolean
        get() = state in setOf(
            EscalationState.ACTION_SUCCEEDED,
            EscalationState.ACTION_FAILED,
            EscalationState.BLOCKED,
            EscalationState.CLOSED,
        )
}
