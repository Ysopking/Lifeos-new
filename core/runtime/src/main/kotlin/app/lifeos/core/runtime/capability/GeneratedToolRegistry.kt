package app.lifeos.core.runtime.capability

import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class GeneratedToolRegistry(
    private val now: () -> Instant = Instant::now,
) {
    data class RollbackMutation(
        val record: GeneratedToolRecord,
        val auditEntry: GeneratedToolAuditEntry,
    )

    private val mutex = Mutex()
    private val records = linkedMapOf<String, GeneratedToolRecord>()
    private val auditEntries = linkedMapOf<String, MutableList<GeneratedToolAuditEntry>>()

    suspend fun register(record: GeneratedToolRecord): GeneratedToolRecord = mutex.withLock {
        require(record.manifest.toolId !in records) {
            "Generated tool ${record.manifest.toolId} already registered"
        }
        require(record.state != GeneratedToolState.ACTIVE) {
            "ACTIVE generated tools must be promoted from TRIAL with guarded evidence"
        }
        records[record.manifest.toolId] = record
        appendAuditLocked(
            before = null,
            after = record,
            action = GeneratedToolAuditAction.REGISTERED,
            occurredAt = now(),
        )
        record
    }

    suspend fun transition(
        toolId: String,
        to: GeneratedToolState,
        confidence: Double? = null,
        message: String? = null,
    ): GeneratedToolRecord = mutex.withLock {
        val current = requireNotNull(records[toolId]) { "Unknown generated tool $toolId" }
        require(current.state != GeneratedToolState.ACTIVE) {
            "ACTIVE generated tool state changes require coordinated rollback"
        }
        require(to != GeneratedToolState.ACTIVE) {
            "ACTIVE transition requires guarded promotion"
        }
        require(message == null || message.isNotBlank()) {
            "Generated tool transition message must not be blank"
        }
        require(to in allowedTransitions.getValue(current.state)) {
            "Invalid generated tool transition ${current.state} -> $to"
        }
        val updated = current.copy(
            state = to,
            verificationConfidence = confidence ?: current.verificationConfidence,
            lastMessage = message,
            promotionEvidenceId = if (to == GeneratedToolState.TRIAL) null else current.promotionEvidenceId,
        )
        records[toolId] = updated
        appendAuditLocked(
            before = current,
            after = updated,
            action = auditActionFor(to),
            reason = message,
            occurredAt = now(),
        )
        updated
    }

    /** Internal mutation primitive. Public activation is owned by the J08 evolution bridge. */
    internal suspend fun promote(
        toolId: String,
        evidence: GeneratedToolPromotionEvidence,
        activationEvidenceRef: String = evidence.id,
        actorId: String? = null,
        message: String = "trial-promoted:${evidence.id}",
    ): GeneratedToolRecord = mutex.withLock {
        require(activationEvidenceRef.isNotBlank()) { "Promotion requires activation evidence reference" }
        require(actorId == null || actorId.isNotBlank()) { "Promotion actor id must not be blank" }
        require(message.isNotBlank()) { "Promotion message must not be blank" }
        val current = requireNotNull(records[toolId]) { "Unknown generated tool $toolId" }
        require(current.state == GeneratedToolState.TRIAL) {
            "Only TRIAL generated tools can be promoted"
        }
        require(evidence.matchesRecord(current)) {
            "J03 promotion evidence does not match current generated tool record"
        }
        val active = current.copy(
            state = GeneratedToolState.ACTIVE,
            lastMessage = message,
            promotionEvidenceId = evidence.id,
        )
        records[toolId] = active
        appendAuditLocked(
            before = current,
            after = active,
            action = GeneratedToolAuditAction.PROMOTED,
            actorId = actorId,
            evidenceRef = activationEvidenceRef,
            reason = "j03-promotion-evidence:${evidence.id}",
            occurredAt = now(),
        )
        active
    }

    /**
     * Reverses one exact active promotion into QUARANTINED. The previous promotion evidence id is
     * retained on the quarantined record for traceability and is cleared only if a later explicit
     * QUARANTINED -> TRIAL transition begins a new trial cycle.
     */
    internal suspend fun rollback(request: GeneratedToolRollbackRequest): RollbackMutation = mutex.withLock {
        val current = requireNotNull(records[request.toolId]) {
            "Unknown generated tool ${request.toolId}"
        }
        require(current.state == GeneratedToolState.ACTIVE) {
            "Only ACTIVE generated tools can be rolled back"
        }
        require(current.promotionEvidenceId == request.expectedPromotionEvidenceId) {
            "Rollback request does not target the active promotion evidence"
        }

        val quarantined = current.copy(
            state = GeneratedToolState.QUARANTINED,
            lastMessage = "rollback:${request.id}:${request.reason}",
        )
        records[request.toolId] = quarantined
        val audit = appendAuditLocked(
            before = current,
            after = quarantined,
            action = GeneratedToolAuditAction.ROLLED_BACK,
            actorId = request.actorId,
            evidenceRef = request.evidenceRef,
            reason = "${request.id}:${request.reason}",
            occurredAt = request.occurredAt,
        )
        RollbackMutation(quarantined, audit)
    }

    suspend fun get(toolId: String): GeneratedToolRecord? = mutex.withLock { records[toolId] }

    suspend fun snapshot(): List<GeneratedToolRecord> = mutex.withLock {
        records.values.sortedBy { it.manifest.toolId }
    }

    suspend fun auditSnapshot(toolId: String): List<GeneratedToolAuditEntry> = mutex.withLock {
        auditEntries[toolId]?.toList().orEmpty()
    }

    /** Verifies hash-chain ordering, state continuity and the final record binding. */
    suspend fun verifyAuditChain(toolId: String): Boolean = mutex.withLock {
        val entries = auditEntries[toolId].orEmpty()
        val record = records[toolId]
        if (entries.isEmpty()) return@withLock record == null
        if (record == null) return@withLock false

        entries.forEachIndexed { index, entry ->
            if (index == 0) {
                if (entry.action != GeneratedToolAuditAction.REGISTERED) return@withLock false
                if (entry.fromState != null || entry.beforeRecordFingerprint != null) return@withLock false
                if (entry.previousEntryId != null) return@withLock false
            } else {
                val previous = entries[index - 1]
                if (entry.previousEntryId != previous.id) return@withLock false
                if (entry.fromState != previous.toState) return@withLock false
                if (entry.beforeRecordFingerprint != previous.afterRecordFingerprint) return@withLock false
            }
        }
        entries.last().afterRecordFingerprint == record.auditFingerprint()
    }

    private fun appendAuditLocked(
        before: GeneratedToolRecord?,
        after: GeneratedToolRecord,
        action: GeneratedToolAuditAction,
        actorId: String? = null,
        evidenceRef: String? = null,
        reason: String? = null,
        occurredAt: Instant,
    ): GeneratedToolAuditEntry {
        val toolId = after.manifest.toolId
        require(before == null || before.manifest.toolId == toolId) {
            "Audit mutation cannot change generated tool identity"
        }
        val entries = auditEntries.getOrPut(toolId) { mutableListOf() }
        val previous = entries.lastOrNull()
        val entry = GeneratedToolAuditEntry(
            toolId = toolId,
            action = action,
            fromState = before?.state,
            toState = after.state,
            beforeRecordFingerprint = before?.auditFingerprint(),
            afterRecordFingerprint = after.auditFingerprint(),
            actorId = actorId,
            evidenceRef = evidenceRef,
            reason = reason,
            occurredAt = occurredAt,
            previousEntryId = previous?.id,
        )
        if (previous == null) {
            require(action == GeneratedToolAuditAction.REGISTERED)
        } else {
            require(entry.beforeRecordFingerprint == previous.afterRecordFingerprint) {
                "Generated-tool audit chain record continuity was broken"
            }
            require(entry.fromState == previous.toState) {
                "Generated-tool audit chain state continuity was broken"
            }
        }
        entries += entry
        return entry
    }

    private fun auditActionFor(to: GeneratedToolState): GeneratedToolAuditAction = when (to) {
        GeneratedToolState.REJECTED -> GeneratedToolAuditAction.REJECTED
        GeneratedToolState.QUARANTINED -> GeneratedToolAuditAction.QUARANTINED
        GeneratedToolState.RETIRED -> GeneratedToolAuditAction.RETIRED
        else -> GeneratedToolAuditAction.TRANSITIONED
    }

    private companion object {
        val allowedTransitions: Map<GeneratedToolState, Set<GeneratedToolState>> = mapOf(
            GeneratedToolState.GENERATED to setOf(GeneratedToolState.BUILT, GeneratedToolState.REJECTED),
            GeneratedToolState.BUILT to setOf(GeneratedToolState.TESTED, GeneratedToolState.REJECTED),
            GeneratedToolState.TESTED to setOf(GeneratedToolState.VERIFIED, GeneratedToolState.REJECTED),
            GeneratedToolState.VERIFIED to setOf(GeneratedToolState.TRIAL, GeneratedToolState.REJECTED),
            GeneratedToolState.TRIAL to setOf(
                GeneratedToolState.QUARANTINED,
                GeneratedToolState.REJECTED,
            ),
            GeneratedToolState.ACTIVE to emptySet(),
            GeneratedToolState.QUARANTINED to setOf(
                GeneratedToolState.TRIAL,
                GeneratedToolState.RETIRED,
            ),
            GeneratedToolState.REJECTED to setOf(GeneratedToolState.RETIRED),
            GeneratedToolState.RETIRED to emptySet(),
        )
    }
}
