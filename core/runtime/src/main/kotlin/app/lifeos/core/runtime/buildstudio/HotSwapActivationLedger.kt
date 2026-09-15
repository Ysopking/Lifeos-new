package app.lifeos.core.runtime.buildstudio

import app.lifeos.core.field.StableFieldIds
import java.time.Instant

@JvmInline
value class HotSwapActivationId(val value: String) {
    init {
        require(value.startsWith(PREFIX)) { "Invalid hot-swap activation id prefix" }
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}"))) {
            "Invalid hot-swap activation id digest"
        }
    }

    override fun toString(): String = value

    companion object {
        const val PREFIX = "hot-swap-activation:"

        fun create(candidate: VerifiedRuntimeCandidate): HotSwapActivationId = HotSwapActivationId(
            PREFIX + StableFieldIds.fingerprint(
                "hot-swap-activation/v1",
                candidate.id,
                candidate.artifact.id,
                candidate.candidateId,
            )
        )
    }
}

enum class HotSwapActivationState {
    PREPARED,
    STAGED,
    HEALTH_VERIFIED,
    ACTIVATION_PREPARED,
    ACTIVATED,
    ROLLED_BACK,
    BLOCKED,
}

data class HotSwapStage(
    val id: String,
    val verifiedCandidateId: String,
    val candidateId: String,
    val previousActiveCandidateId: String? = null,
) {
    init {
        require(id.isNotBlank())
        require(verifiedCandidateId.isNotBlank())
        require(candidateId.isNotBlank())
        require(previousActiveCandidateId == null || previousActiveCandidateId.isNotBlank())
    }
}

data class HotSwapHealthEvidence(
    val id: String,
    val healthy: Boolean,
    val detail: String,
) {
    init {
        require(id.isNotBlank())
        require(detail.isNotBlank())
    }
}

data class HotSwapActivationSnapshot(
    val id: HotSwapActivationId,
    val verifiedCandidateId: String,
    val candidateArtifactId: String,
    val candidateId: String,
    val state: HotSwapActivationState,
    val stageId: String? = null,
    val previousActiveCandidateId: String? = null,
    val healthEvidenceId: String? = null,
    val policyDecisionId: String? = null,
    val lastDetail: String? = null,
    val revision: Long,
    val lastRecordedAt: Instant,
) {
    init {
        require(verifiedCandidateId.isNotBlank())
        require(candidateArtifactId.isNotBlank())
        require(candidateId.isNotBlank())
        require(stageId == null || stageId.isNotBlank())
        require(previousActiveCandidateId == null || previousActiveCandidateId.isNotBlank())
        require(healthEvidenceId == null || healthEvidenceId.isNotBlank())
        require(policyDecisionId == null || policyDecisionId.isNotBlank())
        require(lastDetail == null || lastDetail.isNotBlank())
        require(revision > 0L)
        when (state) {
            HotSwapActivationState.PREPARED -> require(stageId == null)
            HotSwapActivationState.STAGED -> require(stageId != null)
            HotSwapActivationState.HEALTH_VERIFIED,
            HotSwapActivationState.ACTIVATION_PREPARED,
            HotSwapActivationState.ACTIVATED -> {
                require(stageId != null)
                require(healthEvidenceId != null)
            }
            HotSwapActivationState.ROLLED_BACK,
            HotSwapActivationState.BLOCKED -> Unit
        }
        if (state == HotSwapActivationState.ACTIVATION_PREPARED || state == HotSwapActivationState.ACTIVATED) {
            require(policyDecisionId != null)
        }
    }

    val terminal: Boolean
        get() = state == HotSwapActivationState.ACTIVATED ||
            state == HotSwapActivationState.ROLLED_BACK ||
            state == HotSwapActivationState.BLOCKED
}

data class HotSwapActivationRepositoryLoadReport(
    val snapshots: List<HotSwapActivationSnapshot>,
    val unreadableEntries: List<String> = emptyList(),
) {
    init { require(unreadableEntries.none { it.isBlank() }) }
}

/**
 * Durable storage boundary. compareAndSet must atomically store [next] only when the currently
 * persisted revision for [id] equals [expectedRevision]. Revision zero means the id is absent.
 */
interface HotSwapActivationRepository {
    suspend fun loadReport(): HotSwapActivationRepositoryLoadReport

    suspend fun compareAndSet(
        id: HotSwapActivationId,
        expectedRevision: Long,
        next: HotSwapActivationSnapshot,
    ): Boolean
}

class HotSwapConcurrentMutationException(id: HotSwapActivationId) :
    IllegalStateException("Concurrent hot-swap activation mutation: $id")

/** Persistent, CAS-protected activation state machine. */
class HotSwapActivationLedger(
    private val repository: HotSwapActivationRepository,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun open(candidate: VerifiedRuntimeCandidate): HotSwapActivationSnapshot {
        val id = HotSwapActivationId.create(candidate)
        snapshot(id)?.let { existing ->
            require(existing.verifiedCandidateId == candidate.id)
            require(existing.candidateArtifactId == candidate.artifact.id)
            require(existing.candidateId == candidate.candidateId)
            return existing
        }
        val initial = HotSwapActivationSnapshot(
            id = id,
            verifiedCandidateId = candidate.id,
            candidateArtifactId = candidate.artifact.id,
            candidateId = candidate.candidateId,
            state = HotSwapActivationState.PREPARED,
            revision = 1L,
            lastRecordedAt = now(),
        )
        if (!repository.compareAndSet(id, 0L, initial)) {
            return snapshot(id) ?: throw HotSwapConcurrentMutationException(id)
        }
        return initial
    }

    suspend fun markStaged(
        current: HotSwapActivationSnapshot,
        stage: HotSwapStage,
    ): HotSwapActivationSnapshot {
        require(current.state == HotSwapActivationState.PREPARED)
        require(stage.verifiedCandidateId == current.verifiedCandidateId)
        require(stage.candidateId == current.candidateId)
        return advance(
            current = current,
            state = HotSwapActivationState.STAGED,
            stageId = stage.id,
            previousActiveCandidateId = stage.previousActiveCandidateId,
            detail = "candidate-staged",
        )
    }

    suspend fun markHealthVerified(
        current: HotSwapActivationSnapshot,
        evidence: HotSwapHealthEvidence,
    ): HotSwapActivationSnapshot {
        require(current.state == HotSwapActivationState.STAGED)
        require(evidence.healthy) { "Unhealthy evidence cannot advance activation" }
        return advance(
            current = current,
            state = HotSwapActivationState.HEALTH_VERIFIED,
            healthEvidenceId = evidence.id,
            detail = evidence.detail,
        )
    }

    suspend fun markActivationPrepared(
        current: HotSwapActivationSnapshot,
        policyDecisionId: String,
    ): HotSwapActivationSnapshot {
        require(current.state == HotSwapActivationState.HEALTH_VERIFIED)
        require(policyDecisionId.isNotBlank())
        return advance(
            current = current,
            state = HotSwapActivationState.ACTIVATION_PREPARED,
            policyDecisionId = policyDecisionId,
            detail = "activation-side-effect-prepared",
        )
    }

    suspend fun markActivated(
        current: HotSwapActivationSnapshot,
        detail: String = "candidate-activated",
    ): HotSwapActivationSnapshot {
        require(current.state == HotSwapActivationState.ACTIVATION_PREPARED)
        return advance(current, HotSwapActivationState.ACTIVATED, detail = detail)
    }

    suspend fun markRolledBack(
        current: HotSwapActivationSnapshot,
        detail: String,
    ): HotSwapActivationSnapshot {
        require(!current.terminal)
        require(detail.isNotBlank())
        return advance(current, HotSwapActivationState.ROLLED_BACK, detail = detail)
    }

    suspend fun markBlocked(
        current: HotSwapActivationSnapshot,
        detail: String,
    ): HotSwapActivationSnapshot {
        require(!current.terminal)
        require(detail.isNotBlank())
        return advance(current, HotSwapActivationState.BLOCKED, detail = detail)
    }

    suspend fun snapshot(id: HotSwapActivationId): HotSwapActivationSnapshot? =
        checkedSnapshots().singleOrNull { it.id == id }

    suspend fun pending(): List<HotSwapActivationSnapshot> =
        checkedSnapshots().filterNot { it.terminal }.sortedBy { it.id.value }

    private suspend fun checkedSnapshots(): List<HotSwapActivationSnapshot> {
        val report = repository.loadReport()
        check(report.unreadableEntries.isEmpty()) {
            "Cannot reconstruct hot-swap activation ledger with unreadable entries"
        }
        check(report.snapshots.map { it.id }.distinct().size == report.snapshots.size) {
            "Duplicate hot-swap activation snapshots"
        }
        return report.snapshots
    }

    private suspend fun advance(
        current: HotSwapActivationSnapshot,
        state: HotSwapActivationState,
        stageId: String? = current.stageId,
        previousActiveCandidateId: String? = current.previousActiveCandidateId,
        healthEvidenceId: String? = current.healthEvidenceId,
        policyDecisionId: String? = current.policyDecisionId,
        detail: String? = current.lastDetail,
    ): HotSwapActivationSnapshot {
        validateTransition(current.state, state)
        val next = current.copy(
            state = state,
            stageId = stageId,
            previousActiveCandidateId = previousActiveCandidateId,
            healthEvidenceId = healthEvidenceId,
            policyDecisionId = policyDecisionId,
            lastDetail = detail,
            revision = current.revision + 1L,
            lastRecordedAt = now(),
        )
        if (!repository.compareAndSet(current.id, current.revision, next)) {
            throw HotSwapConcurrentMutationException(current.id)
        }
        return next
    }

    private fun validateTransition(from: HotSwapActivationState, to: HotSwapActivationState) {
        val allowed = when (from) {
            HotSwapActivationState.PREPARED -> setOf(
                HotSwapActivationState.STAGED,
                HotSwapActivationState.ROLLED_BACK,
                HotSwapActivationState.BLOCKED,
            )
            HotSwapActivationState.STAGED -> setOf(
                HotSwapActivationState.HEALTH_VERIFIED,
                HotSwapActivationState.ROLLED_BACK,
                HotSwapActivationState.BLOCKED,
            )
            HotSwapActivationState.HEALTH_VERIFIED -> setOf(
                HotSwapActivationState.ACTIVATION_PREPARED,
                HotSwapActivationState.ROLLED_BACK,
                HotSwapActivationState.BLOCKED,
            )
            HotSwapActivationState.ACTIVATION_PREPARED -> setOf(
                HotSwapActivationState.ACTIVATED,
                HotSwapActivationState.ROLLED_BACK,
                HotSwapActivationState.BLOCKED,
            )
            HotSwapActivationState.ACTIVATED,
            HotSwapActivationState.ROLLED_BACK,
            HotSwapActivationState.BLOCKED -> emptySet()
        }
        require(to in allowed) { "Invalid hot-swap activation transition: $from -> $to" }
    }
}
