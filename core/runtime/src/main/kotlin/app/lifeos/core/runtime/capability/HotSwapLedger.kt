package app.lifeos.core.runtime.capability

import app.lifeos.core.field.StableFieldIds
import java.time.Instant

@JvmInline
value class HotSwapTransactionId(val value: String) {
    init { require(value.startsWith(PREFIX)) { "Invalid hot-swap transaction id" } }
    override fun toString(): String = value

    companion object {
        const val PREFIX = "hot-swap:"

        fun create(
            capabilityId: CapabilityId,
            previousToolId: String,
            candidateToolId: String,
            previousPromotionEvidenceId: String,
            candidatePromotionEvidenceId: String,
        ): HotSwapTransactionId = HotSwapTransactionId(
            PREFIX + StableFieldIds.fingerprint(
                "generated-tool-hot-swap/v1",
                capabilityId.value,
                previousToolId,
                candidateToolId,
                previousPromotionEvidenceId,
                candidatePromotionEvidenceId,
            )
        )
    }
}

enum class HotSwapEventType {
    PREPARED,
    CANDIDATE_PROMOTED,
    CUTOVER_COMMITTED,
    REVERT_PREPARED,
    CUTOVER_REVERTED,
    ROLLED_BACK,
    BLOCKED,
}

data class HotSwapEvent(
    val revision: Long,
    val transactionId: HotSwapTransactionId,
    val capabilityId: CapabilityId,
    val previousToolId: String,
    val candidateToolId: String,
    val previousPromotionEvidenceId: String,
    val candidatePromotionEvidenceId: String,
    val type: HotSwapEventType,
    val recordedAt: Instant,
    val ownerPolicyRevision: Long? = null,
    val worldSnapshotId: String? = null,
    val detail: String? = null,
) {
    init {
        require(revision > 0L)
        require(previousToolId.isNotBlank() && candidateToolId.isNotBlank())
        require(previousToolId != candidateToolId)
        require(previousPromotionEvidenceId.isNotBlank())
        require(candidatePromotionEvidenceId.isNotBlank())
        require(ownerPolicyRevision == null || ownerPolicyRevision >= 0L)
        require(worldSnapshotId == null || worldSnapshotId.isNotBlank())
        require(detail == null || detail.isNotBlank())
        require(
            transactionId == HotSwapTransactionId.create(
                capabilityId,
                previousToolId,
                candidateToolId,
                previousPromotionEvidenceId,
                candidatePromotionEvidenceId,
            )
        ) { "Hot-swap transaction id/content mismatch" }
    }
}

data class HotSwapRepositoryLoadReport(
    val events: List<HotSwapEvent>,
    val unreadableEntries: List<String> = emptyList(),
)

interface HotSwapRepository {
    suspend fun loadReport(): HotSwapRepositoryLoadReport
    suspend fun append(expectedRevision: Long, event: HotSwapEvent): Boolean
}

enum class HotSwapState {
    PREPARED,
    CANDIDATE_PROMOTED,
    COMMITTED,
    REVERT_PREPARED,
    REVERTED,
    ROLLED_BACK,
    BLOCKED,
}

data class HotSwapSnapshot(
    val transactionId: HotSwapTransactionId,
    val capabilityId: CapabilityId,
    val previousToolId: String,
    val candidateToolId: String,
    val previousPromotionEvidenceId: String,
    val candidatePromotionEvidenceId: String,
    val state: HotSwapState,
    val ownerPolicyRevision: Long? = null,
    val worldSnapshotId: String? = null,
    val lastDetail: String? = null,
    val ledgerRevision: Long,
) {
    val terminal: Boolean
        get() = state == HotSwapState.COMMITTED ||
            state == HotSwapState.REVERTED ||
            state == HotSwapState.ROLLED_BACK ||
            state == HotSwapState.BLOCKED
}

class HotSwapLedger(
    private val repository: HotSwapRepository,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun prepare(
        capabilityId: CapabilityId,
        previousToolId: String,
        candidateToolId: String,
        previousPromotionEvidenceId: String,
        candidatePromotionEvidenceId: String,
    ): HotSwapSnapshot {
        val id = HotSwapTransactionId.create(
            capabilityId,
            previousToolId,
            candidateToolId,
            previousPromotionEvidenceId,
            candidatePromotionEvidenceId,
        )
        snapshot(id)?.let { return it }
        append(
            id = id,
            capabilityId = capabilityId,
            previousToolId = previousToolId,
            candidateToolId = candidateToolId,
            previousPromotionEvidenceId = previousPromotionEvidenceId,
            candidatePromotionEvidenceId = candidatePromotionEvidenceId,
            type = HotSwapEventType.PREPARED,
        )
        return requireNotNull(snapshot(id))
    }

    suspend fun markCandidatePromoted(
        snapshot: HotSwapSnapshot,
        ownerPolicyRevision: Long,
        worldSnapshotId: String,
    ): HotSwapSnapshot = transition(
        snapshot,
        HotSwapEventType.CANDIDATE_PROMOTED,
        ownerPolicyRevision = ownerPolicyRevision,
        worldSnapshotId = worldSnapshotId,
    )

    suspend fun markCommitted(snapshot: HotSwapSnapshot, detail: String = "routing-cutover-committed"): HotSwapSnapshot =
        transition(snapshot, HotSwapEventType.CUTOVER_COMMITTED, detail = detail)

    /** COMMITTED remains terminal for duplicate swap calls, but may enter one explicit revert flow. */
    suspend fun markRevertPrepared(
        snapshot: HotSwapSnapshot,
        ownerPolicyRevision: Long,
        worldSnapshotId: String,
        detail: String = "post-commit-revert-authorized",
    ): HotSwapSnapshot {
        require(snapshot.state == HotSwapState.COMMITTED) {
            "Only a committed hot-swap can prepare a post-commit revert"
        }
        append(
            id = snapshot.transactionId,
            capabilityId = snapshot.capabilityId,
            previousToolId = snapshot.previousToolId,
            candidateToolId = snapshot.candidateToolId,
            previousPromotionEvidenceId = snapshot.previousPromotionEvidenceId,
            candidatePromotionEvidenceId = snapshot.candidatePromotionEvidenceId,
            type = HotSwapEventType.REVERT_PREPARED,
            ownerPolicyRevision = ownerPolicyRevision,
            worldSnapshotId = worldSnapshotId,
            detail = detail,
        )
        return requireNotNull(snapshot(snapshot.transactionId))
    }

    suspend fun markReverted(
        snapshot: HotSwapSnapshot,
        detail: String = "routing-cutover-reverted",
    ): HotSwapSnapshot = transition(snapshot, HotSwapEventType.CUTOVER_REVERTED, detail = detail)

    suspend fun markRolledBack(snapshot: HotSwapSnapshot, detail: String): HotSwapSnapshot =
        transition(snapshot, HotSwapEventType.ROLLED_BACK, detail = detail)

    suspend fun markBlocked(snapshot: HotSwapSnapshot, detail: String): HotSwapSnapshot =
        transition(snapshot, HotSwapEventType.BLOCKED, detail = detail)

    suspend fun snapshot(id: HotSwapTransactionId): HotSwapSnapshot? {
        val report = loadStrict()
        return replay(report.events.filter { it.transactionId == id })
    }

    suspend fun all(): List<HotSwapSnapshot> {
        val report = loadStrict()
        return report.events.groupBy { it.transactionId }
            .values
            .mapNotNull(::replay)
            .sortedBy { it.transactionId.value }
    }

    suspend fun committed(): List<HotSwapSnapshot> = all().filter { it.state == HotSwapState.COMMITTED }

    private suspend fun transition(
        snapshot: HotSwapSnapshot,
        type: HotSwapEventType,
        ownerPolicyRevision: Long? = snapshot.ownerPolicyRevision,
        worldSnapshotId: String? = snapshot.worldSnapshotId,
        detail: String? = null,
    ): HotSwapSnapshot {
        require(!snapshot.terminal) { "Terminal hot-swap transaction cannot transition" }
        when (type) {
            HotSwapEventType.CANDIDATE_PROMOTED -> require(snapshot.state == HotSwapState.PREPARED)
            HotSwapEventType.CUTOVER_COMMITTED -> require(snapshot.state == HotSwapState.CANDIDATE_PROMOTED)
            HotSwapEventType.CUTOVER_REVERTED -> require(snapshot.state == HotSwapState.REVERT_PREPARED)
            HotSwapEventType.ROLLED_BACK,
            HotSwapEventType.BLOCKED -> require(
                snapshot.state == HotSwapState.PREPARED || snapshot.state == HotSwapState.CANDIDATE_PROMOTED
            )
            HotSwapEventType.REVERT_PREPARED -> error("Use markRevertPrepared for a committed transaction")
            HotSwapEventType.PREPARED -> error("Hot-swap transaction cannot prepare twice")
        }
        append(
            id = snapshot.transactionId,
            capabilityId = snapshot.capabilityId,
            previousToolId = snapshot.previousToolId,
            candidateToolId = snapshot.candidateToolId,
            previousPromotionEvidenceId = snapshot.previousPromotionEvidenceId,
            candidatePromotionEvidenceId = snapshot.candidatePromotionEvidenceId,
            type = type,
            ownerPolicyRevision = ownerPolicyRevision,
            worldSnapshotId = worldSnapshotId,
            detail = detail,
        )
        return requireNotNull(snapshot(snapshot.transactionId))
    }

    private suspend fun append(
        id: HotSwapTransactionId,
        capabilityId: CapabilityId,
        previousToolId: String,
        candidateToolId: String,
        previousPromotionEvidenceId: String,
        candidatePromotionEvidenceId: String,
        type: HotSwapEventType,
        ownerPolicyRevision: Long? = null,
        worldSnapshotId: String? = null,
        detail: String? = null,
    ) {
        repeat(MAX_CAS_ATTEMPTS) {
            val report = loadStrict()
            val current = report.events.lastOrNull()?.revision ?: 0L
            val event = HotSwapEvent(
                revision = current + 1L,
                transactionId = id,
                capabilityId = capabilityId,
                previousToolId = previousToolId,
                candidateToolId = candidateToolId,
                previousPromotionEvidenceId = previousPromotionEvidenceId,
                candidatePromotionEvidenceId = candidatePromotionEvidenceId,
                type = type,
                recordedAt = now(),
                ownerPolicyRevision = ownerPolicyRevision,
                worldSnapshotId = worldSnapshotId,
                detail = detail,
            )
            if (repository.append(current, event)) return
        }
        error("Hot-swap ledger CAS retries exhausted")
    }

    private suspend fun loadStrict(): HotSwapRepositoryLoadReport {
        val report = repository.loadReport()
        check(report.unreadableEntries.isEmpty()) {
            "Hot-swap ledger is unreadable: ${report.unreadableEntries.joinToString(",")}" 
        }
        require(report.events.map { it.revision } == (1L..report.events.size.toLong()).toList()) {
            "Hot-swap event revisions must be contiguous"
        }
        return report
    }

    private fun replay(events: List<HotSwapEvent>): HotSwapSnapshot? {
        if (events.isEmpty()) return null
        val first = events.first()
        require(first.type == HotSwapEventType.PREPARED) { "Hot-swap transaction must begin with PREPARED" }
        require(events.all {
            it.transactionId == first.transactionId &&
                it.capabilityId == first.capabilityId &&
                it.previousToolId == first.previousToolId &&
                it.candidateToolId == first.candidateToolId &&
                it.previousPromotionEvidenceId == first.previousPromotionEvidenceId &&
                it.candidatePromotionEvidenceId == first.candidatePromotionEvidenceId
        }) { "Hot-swap transaction identity changed during replay" }

        var state = HotSwapState.PREPARED
        var ownerRevision: Long? = first.ownerPolicyRevision
        var worldSnapshot: String? = first.worldSnapshotId
        var detail: String? = first.detail
        events.drop(1).forEach { event ->
            when (event.type) {
                HotSwapEventType.PREPARED -> error("Hot-swap transaction cannot prepare twice")
                HotSwapEventType.CANDIDATE_PROMOTED -> {
                    require(state == HotSwapState.PREPARED)
                    state = HotSwapState.CANDIDATE_PROMOTED
                }
                HotSwapEventType.CUTOVER_COMMITTED -> {
                    require(state == HotSwapState.CANDIDATE_PROMOTED)
                    state = HotSwapState.COMMITTED
                }
                HotSwapEventType.REVERT_PREPARED -> {
                    require(state == HotSwapState.COMMITTED)
                    state = HotSwapState.REVERT_PREPARED
                }
                HotSwapEventType.CUTOVER_REVERTED -> {
                    require(state == HotSwapState.REVERT_PREPARED)
                    state = HotSwapState.REVERTED
                }
                HotSwapEventType.ROLLED_BACK -> {
                    require(state == HotSwapState.PREPARED || state == HotSwapState.CANDIDATE_PROMOTED)
                    state = HotSwapState.ROLLED_BACK
                }
                HotSwapEventType.BLOCKED -> {
                    require(state == HotSwapState.PREPARED || state == HotSwapState.CANDIDATE_PROMOTED)
                    state = HotSwapState.BLOCKED
                }
            }
            ownerRevision = event.ownerPolicyRevision ?: ownerRevision
            worldSnapshot = event.worldSnapshotId ?: worldSnapshot
            detail = event.detail ?: detail
        }
        return HotSwapSnapshot(
            transactionId = first.transactionId,
            capabilityId = first.capabilityId,
            previousToolId = first.previousToolId,
            candidateToolId = first.candidateToolId,
            previousPromotionEvidenceId = first.previousPromotionEvidenceId,
            candidatePromotionEvidenceId = first.candidatePromotionEvidenceId,
            state = state,
            ownerPolicyRevision = ownerRevision,
            worldSnapshotId = worldSnapshot,
            lastDetail = detail,
            ledgerRevision = events.last().revision,
        )
    }

    private companion object {
        const val MAX_CAS_ATTEMPTS = 32
    }
}
