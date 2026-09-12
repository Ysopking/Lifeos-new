package app.lifeos.core.runtime.health

import app.lifeos.core.field.StableFieldIds
import java.time.Instant

@JvmInline
value class SelfHealingIncidentId(val value: String) {
    init { require(value.startsWith(PREFIX)) { "Invalid self-healing incident id" } }
    override fun toString(): String = value

    companion object {
        const val PREFIX = "self-healing-incident:"

        fun create(nodeId: HealthNodeId, incidentFingerprint: String, planFingerprint: String): SelfHealingIncidentId {
            require(incidentFingerprint.isNotBlank())
            return SelfHealingIncidentId(
                PREFIX + StableFieldIds.fingerprint(
                    "self-healing-incident/v1",
                    nodeId.value,
                    incidentFingerprint,
                    planFingerprint,
                )
            )
        }
    }
}

enum class SelfHealingEventType {
    OPENED,
    ACTION_PREPARED,
    ACTION_FAILED,
    ACTION_VERIFICATION_FAILED,
    ACTION_INTERRUPTED,
    RECOVERED,
    EXHAUSTED,
    BLOCKED,
    QUARANTINED,
}

data class SelfHealingEvent(
    val revision: Long,
    val incidentId: SelfHealingIncidentId,
    val nodeId: HealthNodeId,
    val planFingerprint: String,
    val type: SelfHealingEventType,
    val recordedAt: Instant,
    val actionIndex: Int? = null,
    val actionId: String? = null,
    val detail: String? = null,
    val evidenceSummary: String? = null,
) {
    init {
        require(revision > 0L)
        require(planFingerprint.isNotBlank())
        require(actionIndex == null || actionIndex >= 0)
        require(actionId == null || actionId.isNotBlank())
        require(detail == null || detail.isNotBlank())
        require(evidenceSummary == null || evidenceSummary.isNotBlank())
        when (type) {
            SelfHealingEventType.ACTION_PREPARED,
            SelfHealingEventType.ACTION_FAILED,
            SelfHealingEventType.ACTION_VERIFICATION_FAILED,
            SelfHealingEventType.ACTION_INTERRUPTED -> {
                require(actionIndex != null && actionId != null) {
                    "$type requires action identity"
                }
            }
            else -> Unit
        }
    }
}

data class SelfHealingRepositoryLoadReport(
    val events: List<SelfHealingEvent>,
    val unreadableEntries: List<String> = emptyList(),
)

interface SelfHealingRepository {
    suspend fun loadReport(): SelfHealingRepositoryLoadReport
    suspend fun append(expectedRevision: Long, event: SelfHealingEvent): Boolean
}

enum class SelfHealingIncidentState {
    OPEN,
    ACTION_IN_FLIGHT,
    RECOVERED,
    EXHAUSTED,
    BLOCKED,
    QUARANTINED,
}

data class SelfHealingIncidentSnapshot(
    val incidentId: SelfHealingIncidentId,
    val nodeId: HealthNodeId,
    val planFingerprint: String,
    val state: SelfHealingIncidentState,
    val nextActionIndex: Int,
    val inFlightActionIndex: Int? = null,
    val inFlightActionId: String? = null,
    val attemptedActionIds: List<String> = emptyList(),
    val lastDetail: String? = null,
    val lastEvidenceSummary: String? = null,
    val ledgerRevision: Long,
    /** Timestamp of the durable event represented by ledgerRevision. */
    val lastRecordedAt: Instant = Instant.EPOCH,
) {
    init {
        require(planFingerprint.isNotBlank())
        require(nextActionIndex >= 0)
        require((inFlightActionIndex == null) == (inFlightActionId == null))
        require(inFlightActionIndex == null || inFlightActionIndex >= 0)
        if (state == SelfHealingIncidentState.ACTION_IN_FLIGHT) require(inFlightActionId != null)
    }

    val terminal: Boolean
        get() = state == SelfHealingIncidentState.RECOVERED ||
            state == SelfHealingIncidentState.EXHAUSTED ||
            state == SelfHealingIncidentState.BLOCKED ||
            state == SelfHealingIncidentState.QUARANTINED
}

fun RecoveryPlan.selfHealingFingerprint(): String = StableFieldIds.fingerprint(
    "self-healing-plan/v1",
    nodeId.value,
    source,
    quarantineOnFailure.toString(),
    *actions.map { "action:${it.id}" }.toTypedArray(),
    *verificationProbes.sortedBy { it.id }.map { "probe:${it.kind.name}:${it.id}:${it.nodeId.value}" }.toTypedArray(),
)

class SelfHealingLedger(
    private val repository: SelfHealingRepository,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun open(
        plan: RecoveryPlan,
        incidentFingerprint: String,
    ): SelfHealingIncidentSnapshot {
        val planFingerprint = plan.selfHealingFingerprint()
        val incidentId = SelfHealingIncidentId.create(plan.nodeId, incidentFingerprint, planFingerprint)
        snapshot(incidentId)?.let { existing ->
            require(existing.nodeId == plan.nodeId && existing.planFingerprint == planFingerprint)
            return existing
        }
        append(incidentId, plan.nodeId, planFingerprint, SelfHealingEventType.OPENED)
        return requireNotNull(snapshot(incidentId))
    }

    suspend fun markPrepared(
        snapshot: SelfHealingIncidentSnapshot,
        actionIndex: Int,
        actionId: String,
    ): SelfHealingIncidentSnapshot {
        require(!snapshot.terminal)
        require(snapshot.state == SelfHealingIncidentState.OPEN)
        require(actionIndex == snapshot.nextActionIndex)
        append(
            snapshot.incidentId,
            snapshot.nodeId,
            snapshot.planFingerprint,
            SelfHealingEventType.ACTION_PREPARED,
            actionIndex,
            actionId,
        )
        return requireNotNull(snapshot(snapshot.incidentId))
    }

    suspend fun markActionFailed(
        snapshot: SelfHealingIncidentSnapshot,
        detail: String,
    ): SelfHealingIncidentSnapshot = finishAction(snapshot, SelfHealingEventType.ACTION_FAILED, detail, null)

    suspend fun markVerificationFailed(
        snapshot: SelfHealingIncidentSnapshot,
        detail: String,
        evidenceSummary: String,
    ): SelfHealingIncidentSnapshot = finishAction(
        snapshot,
        SelfHealingEventType.ACTION_VERIFICATION_FAILED,
        detail,
        evidenceSummary,
    )

    suspend fun markInterrupted(
        snapshot: SelfHealingIncidentSnapshot,
        detail: String,
        evidenceSummary: String,
    ): SelfHealingIncidentSnapshot = finishAction(
        snapshot,
        SelfHealingEventType.ACTION_INTERRUPTED,
        detail,
        evidenceSummary,
    )

    suspend fun markRecovered(
        snapshot: SelfHealingIncidentSnapshot,
        detail: String,
        evidenceSummary: String,
    ): SelfHealingIncidentSnapshot {
        require(!snapshot.terminal)
        append(
            snapshot.incidentId,
            snapshot.nodeId,
            snapshot.planFingerprint,
            SelfHealingEventType.RECOVERED,
            snapshot.inFlightActionIndex,
            snapshot.inFlightActionId,
            detail,
            evidenceSummary,
        )
        return requireNotNull(snapshot(snapshot.incidentId))
    }

    suspend fun markExhausted(snapshot: SelfHealingIncidentSnapshot, detail: String): SelfHealingIncidentSnapshot {
        require(!snapshot.terminal)
        append(snapshot.incidentId, snapshot.nodeId, snapshot.planFingerprint, SelfHealingEventType.EXHAUSTED, detail = detail)
        return requireNotNull(snapshot(snapshot.incidentId))
    }

    suspend fun markBlocked(snapshot: SelfHealingIncidentSnapshot, detail: String): SelfHealingIncidentSnapshot {
        require(!snapshot.terminal)
        append(snapshot.incidentId, snapshot.nodeId, snapshot.planFingerprint, SelfHealingEventType.BLOCKED, detail = detail)
        return requireNotNull(snapshot(snapshot.incidentId))
    }

    suspend fun markQuarantined(snapshot: SelfHealingIncidentSnapshot, detail: String): SelfHealingIncidentSnapshot {
        require(snapshot.state == SelfHealingIncidentState.EXHAUSTED || snapshot.state == SelfHealingIncidentState.OPEN)
        append(snapshot.incidentId, snapshot.nodeId, snapshot.planFingerprint, SelfHealingEventType.QUARANTINED, detail = detail)
        return requireNotNull(snapshot(snapshot.incidentId))
    }

    suspend fun snapshot(incidentId: SelfHealingIncidentId): SelfHealingIncidentSnapshot? {
        val report = repository.loadReport()
        check(report.unreadableEntries.isEmpty()) {
            "Cannot reconstruct self-healing ledger with unreadable entries"
        }
        validateContiguous(report.events)
        return replay(report.events.filter { it.incidentId == incidentId })
    }

    suspend fun active(): List<SelfHealingIncidentSnapshot> {
        val report = repository.loadReport()
        check(report.unreadableEntries.isEmpty()) {
            "Cannot reconstruct self-healing ledger with unreadable entries"
        }
        validateContiguous(report.events)
        return report.events.groupBy { it.incidentId }
            .values
            .mapNotNull(::replay)
            .filterNot { it.terminal }
            .sortedBy { it.incidentId.value }
    }

    private suspend fun finishAction(
        snapshot: SelfHealingIncidentSnapshot,
        type: SelfHealingEventType,
        detail: String,
        evidenceSummary: String?,
    ): SelfHealingIncidentSnapshot {
        require(snapshot.state == SelfHealingIncidentState.ACTION_IN_FLIGHT)
        append(
            snapshot.incidentId,
            snapshot.nodeId,
            snapshot.planFingerprint,
            type,
            snapshot.inFlightActionIndex,
            snapshot.inFlightActionId,
            detail,
            evidenceSummary,
        )
        return requireNotNull(snapshot(snapshot.incidentId))
    }

    private suspend fun append(
        incidentId: SelfHealingIncidentId,
        nodeId: HealthNodeId,
        planFingerprint: String,
        type: SelfHealingEventType,
        actionIndex: Int? = null,
        actionId: String? = null,
        detail: String? = null,
        evidenceSummary: String? = null,
    ) {
        repeat(MAX_CAS_RETRIES) {
            val report = repository.loadReport()
            check(report.unreadableEntries.isEmpty()) {
                "Cannot append self-healing event with unreadable ledger"
            }
            validateContiguous(report.events)
            val revision = report.events.lastOrNull()?.revision ?: 0L
            val event = SelfHealingEvent(
                revision = revision + 1L,
                incidentId = incidentId,
                nodeId = nodeId,
                planFingerprint = planFingerprint,
                type = type,
                recordedAt = now(),
                actionIndex = actionIndex,
                actionId = actionId,
                detail = detail,
                evidenceSummary = evidenceSummary,
            )
            if (repository.append(revision, event)) return
        }
        error("Self-healing ledger CAS retries exhausted")
    }

    private suspend fun loadEvents(): List<SelfHealingEvent> {
        val report = repository.loadReport()
        check(report.unreadableEntries.isEmpty()) {
            "Cannot reconstruct self-healing ledger with unreadable entries"
        }
        validateContiguous(report.events)
        return report.events
    }

    private fun replay(events: List<SelfHealingEvent>): SelfHealingIncidentSnapshot? {
        if (events.isEmpty()) return null
        val first = events.first()
        require(first.type == SelfHealingEventType.OPENED) { "Self-healing incident must begin with OPENED" }
        require(events.all { it.incidentId == first.incidentId })
        require(events.all { it.nodeId == first.nodeId && it.planFingerprint == first.planFingerprint })

        var state = SelfHealingIncidentState.OPEN
        var nextAction = 0
        var inFlightIndex: Int? = null
        var inFlightId: String? = null
        val attempted = mutableListOf<String>()
        var lastDetail: String? = null
        var lastEvidence: String? = null

        events.drop(1).forEach { event ->
            when (event.type) {
                SelfHealingEventType.OPENED -> error("Self-healing incident cannot open twice")
                SelfHealingEventType.ACTION_PREPARED -> {
                    require(state == SelfHealingIncidentState.OPEN)
                    require(event.actionIndex == nextAction)
                    state = SelfHealingIncidentState.ACTION_IN_FLIGHT
                    inFlightIndex = event.actionIndex
                    inFlightId = event.actionId
                    attempted += requireNotNull(event.actionId)
                }
                SelfHealingEventType.ACTION_FAILED,
                SelfHealingEventType.ACTION_VERIFICATION_FAILED,
                SelfHealingEventType.ACTION_INTERRUPTED -> {
                    require(state == SelfHealingIncidentState.ACTION_IN_FLIGHT)
                    require(event.actionIndex == inFlightIndex && event.actionId == inFlightId)
                    state = SelfHealingIncidentState.OPEN
                    nextAction = requireNotNull(inFlightIndex) + 1
                    inFlightIndex = null
                    inFlightId = null
                    lastDetail = event.detail
                    lastEvidence = event.evidenceSummary
                }
                SelfHealingEventType.RECOVERED -> {
                    require(state == SelfHealingIncidentState.ACTION_IN_FLIGHT || state == SelfHealingIncidentState.OPEN)
                    state = SelfHealingIncidentState.RECOVERED
                    lastDetail = event.detail
                    lastEvidence = event.evidenceSummary
                    inFlightIndex = null
                    inFlightId = null
                }
                SelfHealingEventType.EXHAUSTED -> {
                    require(state == SelfHealingIncidentState.OPEN)
                    state = SelfHealingIncidentState.EXHAUSTED
                    lastDetail = event.detail
                }
                SelfHealingEventType.BLOCKED -> {
                    require(state == SelfHealingIncidentState.OPEN || state == SelfHealingIncidentState.ACTION_IN_FLIGHT)
                    state = SelfHealingIncidentState.BLOCKED
                    lastDetail = event.detail
                    inFlightIndex = null
                    inFlightId = null
                }
                SelfHealingEventType.QUARANTINED -> {
                    require(state == SelfHealingIncidentState.EXHAUSTED || state == SelfHealingIncidentState.OPEN)
                    state = SelfHealingIncidentState.QUARANTINED
                    lastDetail = event.detail
                }
            }
        }

        return SelfHealingIncidentSnapshot(
            incidentId = first.incidentId,
            nodeId = first.nodeId,
            planFingerprint = first.planFingerprint,
            state = state,
            nextActionIndex = nextAction,
            inFlightActionIndex = inFlightIndex,
            inFlightActionId = inFlightId,
            attemptedActionIds = attempted.toList(),
            lastDetail = lastDetail,
            lastEvidenceSummary = lastEvidence,
            ledgerRevision = events.last().revision,
            lastRecordedAt = events.last().recordedAt,
        )
    }

    private fun validateContiguous(events: List<SelfHealingEvent>) {
        require(events.map { it.revision } == (1L..events.size.toLong()).toList()) {
            "Self-healing event revisions must be contiguous"
        }
    }

    private companion object {
        const val MAX_CAS_RETRIES = 32
    }
}
