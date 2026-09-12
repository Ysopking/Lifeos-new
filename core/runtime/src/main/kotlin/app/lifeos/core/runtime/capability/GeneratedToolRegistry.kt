package app.lifeos.core.runtime.capability

import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class GeneratedToolRegistry(
    private val now: () -> Instant = Instant::now,
    private val durableState: GeneratedToolStateRepository? = null,
) {
    data class RollbackMutation(
        val record: GeneratedToolRecord,
        val auditEntry: GeneratedToolAuditEntry,
    )

    init {
        GeneratedToolRuntimeProcessRegistry.installTools(this)
    }

    private val mutex = Mutex()
    private val records = linkedMapOf<String, GeneratedToolRecord>()
    private val auditEntries = linkedMapOf<String, MutableList<GeneratedToolAuditEntry>>()

    suspend fun register(record: GeneratedToolRecord): GeneratedToolRecord = mutationLocked {
        require(record.manifest.toolId !in records) {
            "Generated tool ${record.manifest.toolId} already registered"
        }
        require(record.state != GeneratedToolState.ACTIVE) {
            "ACTIVE generated tools must be promoted from TRIAL with guarded evidence"
        }
        val audit = createAuditEntryLocked(
            before = null,
            after = record,
            action = GeneratedToolAuditAction.REGISTERED,
            occurredAt = now(),
        )
        persistAndCommitLocked(record, audit)
        record
    }

    suspend fun transition(
        toolId: String,
        to: GeneratedToolState,
        confidence: Double? = null,
        message: String? = null,
    ): GeneratedToolRecord = mutationLocked {
        val current = requireNotNull(records[toolId]) { "Unknown generated tool $toolId" }
        require(current.state != GeneratedToolState.ACTIVE) {
            "ACTIVE generated tool state changes require coordinated rollback"
        }
        require(to != GeneratedToolState.ACTIVE) { "ACTIVE transition requires guarded promotion" }
        require(message == null || message.isNotBlank()) { "Generated tool transition message must not be blank" }
        require(to in allowedTransitions.getValue(current.state)) {
            "Invalid generated tool transition ${current.state} -> $to"
        }
        val updated = current.copy(
            state = to,
            verificationConfidence = confidence ?: current.verificationConfidence,
            lastMessage = message,
            promotionEvidenceId = if (to == GeneratedToolState.TRIAL) null else current.promotionEvidenceId,
        )
        val audit = createAuditEntryLocked(
            before = current,
            after = updated,
            action = auditActionFor(to),
            reason = message,
            occurredAt = now(),
        )
        persistAndCommitLocked(updated, audit)
        updated
    }

    internal suspend fun promote(
        toolId: String,
        evidence: GeneratedToolActivationEvidence,
        activationEvidenceRef: String = evidence.id,
        actorId: String? = null,
        message: String = "trial-promoted:${evidence.id}",
    ): GeneratedToolRecord = mutationLocked {
        require(activationEvidenceRef.isNotBlank()) { "Promotion requires activation evidence reference" }
        require(actorId == null || actorId.isNotBlank()) { "Promotion actor id must not be blank" }
        require(message.isNotBlank()) { "Promotion message must not be blank" }
        require(!evidence.activationAllowed) { "Activation evidence must remain non-authoritative" }
        val current = requireNotNull(records[toolId]) { "Unknown generated tool $toolId" }
        require(current.state == GeneratedToolState.TRIAL) { "Only TRIAL generated tools can be promoted" }
        require(evidence.matchesRecord(current)) { "Activation evidence does not match current generated tool record" }
        val active = current.copy(
            state = GeneratedToolState.ACTIVE,
            lastMessage = message,
            promotionEvidenceId = evidence.id,
        )
        val audit = createAuditEntryLocked(
            before = current,
            after = active,
            action = GeneratedToolAuditAction.PROMOTED,
            actorId = actorId,
            evidenceRef = activationEvidenceRef,
            reason = evidence.promotionAuditReason(),
            occurredAt = now(),
        )
        persistAndCommitLocked(active, audit, promotionEvidence = evidence)
        active
    }

    internal suspend fun rollback(request: GeneratedToolRollbackRequest): RollbackMutation = mutationLocked {
        val current = requireNotNull(records[request.toolId]) { "Unknown generated tool ${request.toolId}" }
        require(current.state == GeneratedToolState.ACTIVE) { "Only ACTIVE generated tools can be rolled back" }
        require(current.promotionEvidenceId == request.expectedPromotionEvidenceId) {
            "Rollback request does not target the active promotion evidence"
        }
        val quarantined = current.copy(
            state = GeneratedToolState.QUARANTINED,
            lastMessage = "rollback:${request.id}:${request.reason}",
        )
        val audit = createAuditEntryLocked(
            before = current,
            after = quarantined,
            action = GeneratedToolAuditAction.ROLLED_BACK,
            actorId = request.actorId,
            evidenceRef = request.evidenceRef,
            reason = "${request.id}:${request.reason}",
            occurredAt = request.occurredAt,
        )
        persistAndCommitLocked(quarantined, audit)
        RollbackMutation(quarantined, audit)
    }

    suspend fun get(toolId: String): GeneratedToolRecord? = mutex.withLock { records[toolId] }
    suspend fun snapshot(): List<GeneratedToolRecord> = mutex.withLock { records.values.sortedBy { it.manifest.toolId } }
    suspend fun auditSnapshot(toolId: String): List<GeneratedToolAuditEntry> = mutex.withLock {
        auditEntries[toolId]?.toList().orEmpty()
    }

    suspend fun verifyAuditChain(toolId: String): Boolean = mutex.withLock {
        val entries = auditEntries[toolId].orEmpty(); val record = records[toolId]
        if (entries.isEmpty()) return@withLock record == null
        if (record == null) return@withLock false
        runCatching { GeneratedToolStateIntegrity.requireValidAudit(record, entries) }.isSuccess
    }

    internal suspend fun restore(state: GeneratedToolPersistentState) = mutex.withLock {
        val toolId = state.record.manifest.toolId
        require(toolId !in records) { "Generated tool $toolId is already loaded" }
        require(auditEntries[toolId].isNullOrEmpty()) { "Generated tool $toolId audit is already loaded" }
        GeneratedToolStateIntegrity.requireValidAudit(state.record, state.auditEntries)
        records[toolId] = state.record
        auditEntries[toolId] = state.auditEntries.toMutableList()
    }

    private suspend fun persistAndCommitLocked(
        after: GeneratedToolRecord,
        audit: GeneratedToolAuditEntry,
        promotionEvidence: GeneratedToolActivationEvidence? = null,
    ) {
        val toolId = after.manifest.toolId
        val nextAudit = auditEntries[toolId]?.toList().orEmpty() + audit
        val repository = durableState
        if (repository != null) {
            when (promotionEvidence) {
                null -> repository.persistLifecycle(after, nextAudit, promotionEvidence = null)
                is GeneratedToolPromotionEvidence -> repository.persistLifecycle(after, nextAudit, promotionEvidence)
                is BoundedGeneratedToolPromotionEvidence -> {
                    val bounded = repository as? BoundedGeneratedToolStateRepository
                        ?: error("Bounded promotion requires a bounded durable state repository")
                    bounded.persistBoundedLifecycle(after, nextAudit, promotionEvidence)
                }
            }
        }
        records[toolId] = after
        auditEntries.getOrPut(toolId) { mutableListOf() } += audit
    }

    private fun createAuditEntryLocked(
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
        val previous = auditEntries[toolId]?.lastOrNull()
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
            require(entry.fromState == previous.toState) { "Generated-tool audit chain state continuity was broken" }
        }
        return entry
    }

    private suspend fun <T> mutationLocked(action: suspend () -> T): T {
        mutex.lock()
        return try { action() } finally { mutex.unlock() }
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
            GeneratedToolState.TRIAL to setOf(GeneratedToolState.QUARANTINED, GeneratedToolState.REJECTED),
            GeneratedToolState.ACTIVE to emptySet(),
            GeneratedToolState.QUARANTINED to setOf(GeneratedToolState.TRIAL, GeneratedToolState.RETIRED),
            GeneratedToolState.REJECTED to setOf(GeneratedToolState.RETIRED),
            GeneratedToolState.RETIRED to emptySet(),
        )
    }
}
