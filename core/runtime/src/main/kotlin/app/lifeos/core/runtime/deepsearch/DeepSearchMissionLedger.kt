package app.lifeos.core.runtime.deepsearch

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.PhotonId
import java.time.Instant

@JvmInline
value class DeepSearchMissionId(val value: String) {
    init {
        require(value.startsWith(PREFIX)) { "Invalid DeepSearch mission id prefix" }
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}"))) {
            "Invalid DeepSearch mission id digest"
        }
    }

    override fun toString(): String = value

    companion object {
        const val PREFIX = "deep-search-mission_"
    }
}

data class DeepSearchMissionDefinition(
    val id: DeepSearchMissionId,
    val goalPhotonId: PhotonId,
    val sourcePhotonId: PhotonId,
    val sourceRevision: Long,
    val query: String,
    val searchPolicyVersion: String,
    val sourceScopeIds: Set<String>,
    val createdAt: Instant,
) {
    init {
        require(sourceRevision > 0L)
        require(query.isNotBlank())
        require(searchPolicyVersion.isNotBlank())
        require(sourceScopeIds.isNotEmpty())
        require(sourceScopeIds.none { it.isBlank() })
        require(id == createId(
            goalPhotonId = goalPhotonId,
            sourcePhotonId = sourcePhotonId,
            sourceRevision = sourceRevision,
            query = query,
            searchPolicyVersion = searchPolicyVersion,
            sourceScopeIds = sourceScopeIds,
        )) { "DeepSearch mission id/content mismatch" }
    }

    companion object {
        fun create(
            goalPhotonId: PhotonId,
            sourcePhotonId: PhotonId,
            sourceRevision: Long,
            query: String,
            searchPolicyVersion: String,
            sourceScopeIds: Set<String>,
            createdAt: Instant,
        ): DeepSearchMissionDefinition = DeepSearchMissionDefinition(
            id = createId(
                goalPhotonId,
                sourcePhotonId,
                sourceRevision,
                query,
                searchPolicyVersion,
                sourceScopeIds,
            ),
            goalPhotonId = goalPhotonId,
            sourcePhotonId = sourcePhotonId,
            sourceRevision = sourceRevision,
            query = normalizeSearchText(query),
            searchPolicyVersion = searchPolicyVersion,
            sourceScopeIds = sourceScopeIds.toSortedSet(),
            createdAt = createdAt,
        )

        private fun createId(
            goalPhotonId: PhotonId,
            sourcePhotonId: PhotonId,
            sourceRevision: Long,
            query: String,
            searchPolicyVersion: String,
            sourceScopeIds: Set<String>,
        ) = DeepSearchMissionId(
            DeepSearchMissionId.PREFIX + StableFieldIds.fingerprint(
                "deep-search-mission/v2",
                goalPhotonId.value,
                sourcePhotonId.value,
                sourceRevision.toString(),
                normalizeSearchText(query),
                searchPolicyVersion,
                *sourceScopeIds.sorted().map { "scope:$it" }.toTypedArray(),
            )
        )
    }
}

enum class DeepSearchMissionState {
    PLANNED,
    EXPLORING,
    SYNTHESIZING,
    VERIFYING,
    COMPLETED,
    UNRESOLVED,
    BLOCKED,
    CANCELLED,
}

enum class DeepSearchMissionEventType {
    PLANNED,
    EXPLORATION_STARTED,
    CHECKPOINTED,
    SYNTHESIS_STARTED,
    VERIFICATION_STARTED,
    COMPLETED,
    UNRESOLVED,
    BLOCKED,
    CANCELLED,
}

data class DeepSearchMissionEvent(
    val revision: Long,
    val missionId: DeepSearchMissionId,
    val type: DeepSearchMissionEventType,
    val recordedAt: Instant,
    val definition: DeepSearchMissionDefinition? = null,
    val checkpointFingerprint: String? = null,
    val resultPhotonId: PhotonId? = null,
    val detail: String? = null,
) {
    init {
        require(revision > 0L)
        require(checkpointFingerprint == null || checkpointFingerprint.isNotBlank())
        require(detail == null || detail.isNotBlank())
        when (type) {
            DeepSearchMissionEventType.PLANNED -> {
                require(definition != null && definition.id == missionId)
                require(checkpointFingerprint == null && resultPhotonId == null)
            }
            else -> require(definition == null) {
                "DeepSearch definition belongs only to the PLANNED event"
            }
        }
        if (resultPhotonId != null) {
            require(type == DeepSearchMissionEventType.COMPLETED || type == DeepSearchMissionEventType.UNRESOLVED) {
                "Only terminal synthesis may bind a DeepSearch result Photon"
            }
        }
    }
}

data class DeepSearchMissionRepositoryLoadReport(
    val events: List<DeepSearchMissionEvent>,
    val unreadableEntries: List<String> = emptyList(),
)

interface DeepSearchMissionRepository {
    suspend fun loadReport(): DeepSearchMissionRepositoryLoadReport
    suspend fun append(expectedRevision: Long, event: DeepSearchMissionEvent): Boolean
}

data class DeepSearchMissionSnapshot(
    val definition: DeepSearchMissionDefinition,
    val state: DeepSearchMissionState,
    val checkpointFingerprint: String? = null,
    val resultPhotonId: PhotonId? = null,
    val lastDetail: String? = null,
    val ledgerRevision: Long,
) {
    val terminal: Boolean
        get() = state in setOf(
            DeepSearchMissionState.COMPLETED,
            DeepSearchMissionState.UNRESOLVED,
            DeepSearchMissionState.BLOCKED,
            DeepSearchMissionState.CANCELLED,
        )
}

class DeepSearchMissionLedger(
    private val repository: DeepSearchMissionRepository,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun create(definition: DeepSearchMissionDefinition): DeepSearchMissionSnapshot {
        snapshot(definition.id)?.let { existing ->
            require(existing.definition == definition) { "DeepSearch mission identity collision" }
            return existing
        }
        append(
            missionId = definition.id,
            type = DeepSearchMissionEventType.PLANNED,
            definition = definition,
        )
        return requireNotNull(snapshot(definition.id))
    }

    suspend fun startExploring(snapshot: DeepSearchMissionSnapshot): DeepSearchMissionSnapshot =
        transition(snapshot, DeepSearchMissionEventType.EXPLORATION_STARTED)

    suspend fun checkpoint(
        snapshot: DeepSearchMissionSnapshot,
        checkpointFingerprint: String,
    ): DeepSearchMissionSnapshot {
        require(snapshot.state == DeepSearchMissionState.EXPLORING)
        require(checkpointFingerprint.isNotBlank())
        if (snapshot.checkpointFingerprint == checkpointFingerprint) return snapshot
        return transition(
            snapshot,
            DeepSearchMissionEventType.CHECKPOINTED,
            checkpointFingerprint = checkpointFingerprint,
        )
    }

    suspend fun startSynthesizing(snapshot: DeepSearchMissionSnapshot): DeepSearchMissionSnapshot =
        transition(snapshot, DeepSearchMissionEventType.SYNTHESIS_STARTED)

    suspend fun startVerifying(snapshot: DeepSearchMissionSnapshot): DeepSearchMissionSnapshot =
        transition(snapshot, DeepSearchMissionEventType.VERIFICATION_STARTED)

    suspend fun complete(
        snapshot: DeepSearchMissionSnapshot,
        resultPhotonId: PhotonId,
        detail: String = "deep-search-result-persisted",
    ): DeepSearchMissionSnapshot = transition(
        snapshot,
        DeepSearchMissionEventType.COMPLETED,
        resultPhotonId = resultPhotonId,
        detail = detail,
    )

    suspend fun unresolved(
        snapshot: DeepSearchMissionSnapshot,
        resultPhotonId: PhotonId,
        detail: String = "deep-search-remains-unresolved",
    ): DeepSearchMissionSnapshot = transition(
        snapshot,
        DeepSearchMissionEventType.UNRESOLVED,
        resultPhotonId = resultPhotonId,
        detail = detail,
    )

    suspend fun block(snapshot: DeepSearchMissionSnapshot, detail: String): DeepSearchMissionSnapshot =
        transition(snapshot, DeepSearchMissionEventType.BLOCKED, detail = detail)

    suspend fun cancel(snapshot: DeepSearchMissionSnapshot, detail: String): DeepSearchMissionSnapshot =
        transition(snapshot, DeepSearchMissionEventType.CANCELLED, detail = detail)

    suspend fun snapshot(id: DeepSearchMissionId): DeepSearchMissionSnapshot? {
        val events = loadStrict().events.filter { it.missionId == id }
        return replay(events)
    }

    suspend fun all(): List<DeepSearchMissionSnapshot> = loadStrict().events
        .groupBy { it.missionId }
        .values
        .mapNotNull(::replay)
        .sortedBy { it.definition.id.value }

    suspend fun active(): List<DeepSearchMissionSnapshot> = all().filterNot { it.terminal }

    private suspend fun transition(
        snapshot: DeepSearchMissionSnapshot,
        type: DeepSearchMissionEventType,
        checkpointFingerprint: String? = snapshot.checkpointFingerprint,
        resultPhotonId: PhotonId? = null,
        detail: String? = null,
    ): DeepSearchMissionSnapshot {
        require(!snapshot.terminal) { "Terminal DeepSearch mission cannot transition" }
        when (type) {
            DeepSearchMissionEventType.EXPLORATION_STARTED -> require(snapshot.state == DeepSearchMissionState.PLANNED)
            DeepSearchMissionEventType.CHECKPOINTED -> require(snapshot.state == DeepSearchMissionState.EXPLORING)
            DeepSearchMissionEventType.SYNTHESIS_STARTED -> require(snapshot.state == DeepSearchMissionState.EXPLORING)
            DeepSearchMissionEventType.VERIFICATION_STARTED -> require(snapshot.state == DeepSearchMissionState.SYNTHESIZING)
            DeepSearchMissionEventType.COMPLETED,
            DeepSearchMissionEventType.UNRESOLVED -> require(snapshot.state == DeepSearchMissionState.VERIFYING)
            DeepSearchMissionEventType.BLOCKED,
            DeepSearchMissionEventType.CANCELLED -> Unit
            DeepSearchMissionEventType.PLANNED -> error("Use create() for DeepSearch mission planning")
        }
        append(
            missionId = snapshot.definition.id,
            type = type,
            checkpointFingerprint = checkpointFingerprint,
            resultPhotonId = resultPhotonId,
            detail = detail,
        )
        return requireNotNull(snapshot(snapshot.definition.id))
    }

    private suspend fun append(
        missionId: DeepSearchMissionId,
        type: DeepSearchMissionEventType,
        definition: DeepSearchMissionDefinition? = null,
        checkpointFingerprint: String? = null,
        resultPhotonId: PhotonId? = null,
        detail: String? = null,
    ) {
        repeat(MAX_CAS_ATTEMPTS) {
            val report = loadStrict()
            val current = report.events.lastOrNull()?.revision ?: 0L
            val event = DeepSearchMissionEvent(
                revision = current + 1L,
                missionId = missionId,
                type = type,
                recordedAt = now(),
                definition = definition,
                checkpointFingerprint = checkpointFingerprint,
                resultPhotonId = resultPhotonId,
                detail = detail,
            )
            if (repository.append(current, event)) return
        }
        error("DeepSearch mission ledger CAS retries exhausted")
    }

    private suspend fun loadStrict(): DeepSearchMissionRepositoryLoadReport {
        val report = repository.loadReport()
        check(report.unreadableEntries.isEmpty()) {
            "DeepSearch mission ledger is unreadable: ${report.unreadableEntries.joinToString(",")}" 
        }
        require(report.events.map { it.revision } == (1L..report.events.size.toLong()).toList()) {
            "DeepSearch mission revisions must be contiguous"
        }
        return report
    }

    private fun replay(events: List<DeepSearchMissionEvent>): DeepSearchMissionSnapshot? {
        if (events.isEmpty()) return null
        val first = events.first()
        require(first.type == DeepSearchMissionEventType.PLANNED)
        val definition = requireNotNull(first.definition)
        require(events.all { it.missionId == definition.id }) { "DeepSearch mission identity changed during replay" }
        var state = DeepSearchMissionState.PLANNED
        var checkpoint: String? = null
        var resultPhotonId: PhotonId? = null
        var detail: String? = first.detail
        events.drop(1).forEach { event ->
            when (event.type) {
                DeepSearchMissionEventType.PLANNED -> error("DeepSearch mission cannot be planned twice")
                DeepSearchMissionEventType.EXPLORATION_STARTED -> {
                    require(state == DeepSearchMissionState.PLANNED)
                    state = DeepSearchMissionState.EXPLORING
                }
                DeepSearchMissionEventType.CHECKPOINTED -> {
                    require(state == DeepSearchMissionState.EXPLORING)
                    checkpoint = requireNotNull(event.checkpointFingerprint)
                }
                DeepSearchMissionEventType.SYNTHESIS_STARTED -> {
                    require(state == DeepSearchMissionState.EXPLORING)
                    state = DeepSearchMissionState.SYNTHESIZING
                }
                DeepSearchMissionEventType.VERIFICATION_STARTED -> {
                    require(state == DeepSearchMissionState.SYNTHESIZING)
                    state = DeepSearchMissionState.VERIFYING
                }
                DeepSearchMissionEventType.COMPLETED -> {
                    require(state == DeepSearchMissionState.VERIFYING)
                    resultPhotonId = requireNotNull(event.resultPhotonId)
                    state = DeepSearchMissionState.COMPLETED
                }
                DeepSearchMissionEventType.UNRESOLVED -> {
                    require(state == DeepSearchMissionState.VERIFYING)
                    resultPhotonId = requireNotNull(event.resultPhotonId)
                    state = DeepSearchMissionState.UNRESOLVED
                }
                DeepSearchMissionEventType.BLOCKED -> state = DeepSearchMissionState.BLOCKED
                DeepSearchMissionEventType.CANCELLED -> state = DeepSearchMissionState.CANCELLED
            }
            checkpoint = event.checkpointFingerprint ?: checkpoint
            detail = event.detail ?: detail
        }
        return DeepSearchMissionSnapshot(
            definition = definition,
            state = state,
            checkpointFingerprint = checkpoint,
            resultPhotonId = resultPhotonId,
            lastDetail = detail,
            ledgerRevision = events.last().revision,
        )
    }

    private companion object {
        const val MAX_CAS_ATTEMPTS = 32
    }
}
