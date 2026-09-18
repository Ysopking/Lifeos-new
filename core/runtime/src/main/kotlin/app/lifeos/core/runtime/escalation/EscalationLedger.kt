package app.lifeos.core.runtime.escalation

import java.time.Instant

class EscalationLedger(
    private val repository: EscalationRepository,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun open(trigger: EscalationTrigger): EscalationSnapshot {
        snapshot(trigger.id)?.let { existing ->
            require(existing.nodeId == trigger.nodeId)
            require(existing.triggerFingerprint == trigger.fingerprint)
            return existing
        }
        append(
            escalationId = trigger.id,
            nodeId = trigger.nodeId,
            triggerFingerprint = trigger.fingerprint,
            type = EscalationRecordType.OPENED,
            recordedAt = trigger.observedAt,
            evidenceRefs = trigger.evidenceRefs,
        )
        return requireNotNull(snapshot(trigger.id))
    }

    suspend fun openDecided(
        trigger: EscalationTrigger,
        decision: EscalationDecision,
    ): EscalationSnapshot {
        require(decision.escalationId == trigger.id)
        require(decision.triggerFingerprint == trigger.fingerprint)
        snapshot(trigger.id)?.let { existing ->
            require(existing.nodeId == trigger.nodeId)
            require(existing.triggerFingerprint == trigger.fingerprint)
            return if (existing.state == EscalationState.OPEN) {
                decide(existing, decision)
            } else {
                require(existing.level == decision.level) {
                    "Existing escalation decision differs from deterministic policy"
                }
                existing
            }
        }
        append(
            escalationId = trigger.id,
            nodeId = trigger.nodeId,
            triggerFingerprint = trigger.fingerprint,
            type = EscalationRecordType.OPENED,
            recordedAt = decision.decidedAt,
            level = decision.level,
            detail = decision.reasonCodes.joinToString("|"),
            evidenceRefs = trigger.evidenceRefs,
        )
        return requireNotNull(snapshot(trigger.id))
    }

    suspend fun decide(
        snapshot: EscalationSnapshot,
        decision: EscalationDecision,
    ): EscalationSnapshot {
        require(snapshot.state == EscalationState.OPEN)
        require(decision.escalationId == snapshot.escalationId)
        require(decision.triggerFingerprint == snapshot.triggerFingerprint)
        append(
            escalationId = snapshot.escalationId,
            nodeId = snapshot.nodeId,
            triggerFingerprint = snapshot.triggerFingerprint,
            type = EscalationRecordType.DECIDED,
            recordedAt = decision.decidedAt,
            level = decision.level,
            detail = decision.reasonCodes.joinToString("|"),
            evidenceRefs = snapshot.evidenceRefs,
        )
        return requireNotNull(snapshot(snapshot.escalationId))
    }

    suspend fun markActionStarted(
        snapshot: EscalationSnapshot,
        detail: String,
        evidenceRefs: Set<String> = emptySet(),
    ): EscalationSnapshot {
        require(snapshot.state == EscalationState.DECIDED)
        return transition(snapshot, EscalationRecordType.ACTION_STARTED, detail, evidenceRefs)
    }

    suspend fun markActionSucceeded(
        snapshot: EscalationSnapshot,
        detail: String,
        evidenceRefs: Set<String> = emptySet(),
    ): EscalationSnapshot {
        require(snapshot.state == EscalationState.ACTION_IN_FLIGHT)
        return transition(snapshot, EscalationRecordType.ACTION_SUCCEEDED, detail, evidenceRefs)
    }

    suspend fun markActionFailed(
        snapshot: EscalationSnapshot,
        detail: String,
        evidenceRefs: Set<String> = emptySet(),
    ): EscalationSnapshot {
        require(snapshot.state == EscalationState.ACTION_IN_FLIGHT)
        return transition(snapshot, EscalationRecordType.ACTION_FAILED, detail, evidenceRefs)
    }

    suspend fun markBlocked(
        snapshot: EscalationSnapshot,
        detail: String,
        evidenceRefs: Set<String> = emptySet(),
    ): EscalationSnapshot {
        require(snapshot.state == EscalationState.DECIDED || snapshot.state == EscalationState.ACTION_IN_FLIGHT)
        return transition(snapshot, EscalationRecordType.BLOCKED, detail, evidenceRefs)
    }

    suspend fun close(
        snapshot: EscalationSnapshot,
        detail: String,
        evidenceRefs: Set<String> = emptySet(),
    ): EscalationSnapshot {
        require(snapshot.state != EscalationState.OPEN && snapshot.state != EscalationState.CLOSED)
        return transition(snapshot, EscalationRecordType.CLOSED, detail, evidenceRefs)
    }

    suspend fun snapshot(id: EscalationId): EscalationSnapshot? {
        val records = loadRecords()
        return replay(records.filter { it.escalationId == id })
    }

    suspend fun active(): List<EscalationSnapshot> =
        loadRecords()
            .groupBy { it.escalationId }
            .values
            .mapNotNull(::replay)
            .filterNot { it.terminal }
            .sortedBy { it.escalationId.value }

    private suspend fun transition(
        snapshot: EscalationSnapshot,
        type: EscalationRecordType,
        detail: String,
        evidenceRefs: Set<String>,
    ): EscalationSnapshot {
        require(detail.isNotBlank())
        append(
            escalationId = snapshot.escalationId,
            nodeId = snapshot.nodeId,
            triggerFingerprint = snapshot.triggerFingerprint,
            type = type,
            recordedAt = now(),
            level = snapshot.level,
            detail = detail,
            evidenceRefs = snapshot.evidenceRefs + evidenceRefs,
        )
        return requireNotNull(snapshot(snapshot.escalationId))
    }

    private suspend fun append(
        escalationId: EscalationId,
        nodeId: app.lifeos.core.runtime.health.HealthNodeId,
        triggerFingerprint: String,
        type: EscalationRecordType,
        recordedAt: Instant,
        level: EscalationLevel? = null,
        detail: String? = null,
        evidenceRefs: Set<String> = emptySet(),
    ) {
        repeat(MAX_CAS_RETRIES) {
            val records = loadRecords()
            val revision = records.lastOrNull()?.revision ?: 0L
            val record = EscalationRecord(
                revision = revision + 1L,
                escalationId = escalationId,
                nodeId = nodeId,
                triggerFingerprint = triggerFingerprint,
                type = type,
                recordedAt = recordedAt,
                level = level,
                detail = detail,
                evidenceRefs = evidenceRefs,
            )
            if (repository.append(revision, record)) return
        }
        error("Escalation ledger CAS retries exhausted")
    }

    private suspend fun loadRecords(): List<EscalationRecord> {
        val report = repository.loadReport()
        check(report.unreadableEntries.isEmpty()) {
            "Cannot reconstruct escalation ledger with unreadable entries"
        }
        validateGlobalRevisions(report.records)
        return report.records.sortedBy { it.revision }
    }

    private fun replay(records: List<EscalationRecord>): EscalationSnapshot? {
        if (records.isEmpty()) return null
        val first = records.first()
        require(first.type == EscalationRecordType.OPENED) {
            "Escalation must begin with OPENED"
        }
        require(records.all { it.escalationId == first.escalationId })
        require(records.all { it.nodeId == first.nodeId })
        require(records.all { it.triggerFingerprint == first.triggerFingerprint })

        var level: EscalationLevel? = first.level
        var state = if (level == null) EscalationState.OPEN else EscalationState.DECIDED
        var detail: String? = first.detail
        val evidence = linkedSetOf<String>()
        evidence += first.evidenceRefs

        records.drop(1).forEach { record ->
            evidence += record.evidenceRefs
            when (record.type) {
                EscalationRecordType.OPENED -> error("Escalation cannot open twice")
                EscalationRecordType.DECIDED -> {
                    require(state == EscalationState.OPEN)
                    level = requireNotNull(record.level)
                    state = EscalationState.DECIDED
                }
                EscalationRecordType.ACTION_STARTED -> {
                    require(state == EscalationState.DECIDED)
                    require(record.level == level)
                    state = EscalationState.ACTION_IN_FLIGHT
                }
                EscalationRecordType.ACTION_SUCCEEDED -> {
                    require(state == EscalationState.ACTION_IN_FLIGHT)
                    require(record.level == level)
                    state = EscalationState.ACTION_SUCCEEDED
                }
                EscalationRecordType.ACTION_FAILED -> {
                    require(state == EscalationState.ACTION_IN_FLIGHT)
                    require(record.level == level)
                    state = EscalationState.ACTION_FAILED
                }
                EscalationRecordType.BLOCKED -> {
                    require(state == EscalationState.DECIDED || state == EscalationState.ACTION_IN_FLIGHT)
                    require(record.level == level)
                    state = EscalationState.BLOCKED
                }
                EscalationRecordType.CLOSED -> {
                    require(state != EscalationState.OPEN && state != EscalationState.CLOSED)
                    state = EscalationState.CLOSED
                }
            }
            detail = record.detail ?: detail
        }

        return EscalationSnapshot(
            escalationId = first.escalationId,
            nodeId = first.nodeId,
            triggerFingerprint = first.triggerFingerprint,
            state = state,
            level = level,
            lastDetail = detail,
            evidenceRefs = evidence,
            ledgerRevision = records.last().revision,
            lastRecordedAt = records.last().recordedAt,
        )
    }

    private fun validateGlobalRevisions(records: List<EscalationRecord>) {
        val revisions = records.map { it.revision }.sorted()
        require(revisions == if (revisions.isEmpty()) emptyList() else (1L..revisions.last()).toList()) {
            "Escalation record revisions must be globally contiguous"
        }
    }

    private companion object {
        const val MAX_CAS_RETRIES = 32
    }
}
