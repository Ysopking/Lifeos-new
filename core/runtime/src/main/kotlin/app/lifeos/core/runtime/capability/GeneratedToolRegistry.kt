package app.lifeos.core.runtime.capability

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class GeneratedToolRegistry {
    private val mutex = Mutex()
    private val records = linkedMapOf<String, GeneratedToolRecord>()
    private val promotionEvidenceIds = linkedMapOf<String, String>()

    suspend fun register(record: GeneratedToolRecord): GeneratedToolRecord = mutex.withLock {
        require(record.manifest.toolId !in records) {
            "Generated tool ${record.manifest.toolId} already registered"
        }
        records[record.manifest.toolId] = record
        record
    }

    suspend fun transition(
        toolId: String,
        to: GeneratedToolState,
        confidence: Double? = null,
        message: String? = null,
    ): GeneratedToolRecord = mutex.withLock {
        val current = requireNotNull(records[toolId]) { "Unknown generated tool $toolId" }
        require(to in allowedTransitions.getValue(current.state)) {
            "Invalid generated tool transition ${current.state} -> $to"
        }
        val updated = current.copy(
            state = to,
            verificationConfidence = confidence ?: current.verificationConfidence,
            lastMessage = message,
        )
        records[toolId] = updated
        updated
    }

    suspend fun bindPromotionEvidence(toolId: String, evidenceId: String): String = mutex.withLock {
        val current = requireNotNull(records[toolId]) { "Unknown generated tool $toolId" }
        require(current.state == GeneratedToolState.TRIAL) {
            "Promotion evidence may only bind a TRIAL tool"
        }
        require(evidenceId.isNotBlank()) { "Promotion evidence id must not be blank" }
        val existing = promotionEvidenceIds[toolId]
        if (existing != null) {
            require(existing == evidenceId) { "Conflicting promotion evidence for $toolId" }
            return@withLock existing
        }
        promotionEvidenceIds[toolId] = evidenceId
        evidenceId
    }

    suspend fun promotionEvidenceId(toolId: String): String? = mutex.withLock {
        require(toolId.isNotBlank())
        promotionEvidenceIds[toolId]
    }

    suspend fun get(toolId: String): GeneratedToolRecord? = mutex.withLock { records[toolId] }

    suspend fun snapshot(): List<GeneratedToolRecord> = mutex.withLock {
        records.values.sortedBy { it.manifest.toolId }
    }

    private companion object {
        val allowedTransitions: Map<GeneratedToolState, Set<GeneratedToolState>> = mapOf(
            GeneratedToolState.GENERATED to setOf(GeneratedToolState.BUILT, GeneratedToolState.REJECTED),
            GeneratedToolState.BUILT to setOf(GeneratedToolState.TESTED, GeneratedToolState.REJECTED),
            GeneratedToolState.TESTED to setOf(GeneratedToolState.VERIFIED, GeneratedToolState.REJECTED),
            GeneratedToolState.VERIFIED to setOf(GeneratedToolState.TRIAL, GeneratedToolState.REJECTED),
            GeneratedToolState.TRIAL to setOf(
                GeneratedToolState.ACTIVE,
                GeneratedToolState.QUARANTINED,
                GeneratedToolState.REJECTED,
            ),
            GeneratedToolState.ACTIVE to setOf(
                GeneratedToolState.QUARANTINED,
                GeneratedToolState.RETIRED,
            ),
            GeneratedToolState.QUARANTINED to setOf(
                GeneratedToolState.TRIAL,
                GeneratedToolState.RETIRED,
            ),
            GeneratedToolState.REJECTED to setOf(GeneratedToolState.RETIRED),
            GeneratedToolState.RETIRED to emptySet(),
        )
    }
}
