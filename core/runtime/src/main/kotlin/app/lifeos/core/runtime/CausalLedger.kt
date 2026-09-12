package app.lifeos.core.runtime

import app.lifeos.core.model.CausalTraceId
import app.lifeos.core.model.CognitiveBranch
import app.lifeos.core.model.CognitiveIntegrationRecord
import app.lifeos.core.model.ModuleProcessingRecord
import app.lifeos.core.model.PhotonId

/** Immutable snapshot of one completed causal cognition run. */
data class CausalLedgerEntry(
    val traceId: CausalTraceId,
    val rootPhotonId: PhotonId,
    val attraction: FieldAttractionPlan,
    val branches: List<CognitiveBranch>,
    val processingRecords: List<ModuleProcessingRecord>,
    val integration: CognitiveIntegrationRecord?,
    val emittedPhotonIds: List<PhotonId>,
) {
    init {
        require(branches.all { it.traceId == traceId }) { "All branches must belong to the trace" }
        require(processingRecords.all { it.traceId == traceId }) { "All processing records must belong to the trace" }
        require(integration == null || integration.traceId == traceId) { "Integration must belong to the trace" }
        require(emittedPhotonIds.distinct().size == emittedPhotonIds.size) { "Emitted photon ids must be unique" }
    }
}

interface CausalLedgerStore {
    suspend fun append(entry: CausalLedgerEntry)
    suspend fun load(traceId: CausalTraceId): CausalLedgerEntry?
    suspend fun contains(traceId: CausalTraceId): Boolean = load(traceId) != null
}

class InMemoryCausalLedgerStore : CausalLedgerStore {
    private val entries = linkedMapOf<CausalTraceId, CausalLedgerEntry>()

    @Synchronized
    override suspend fun append(entry: CausalLedgerEntry) {
        val existing = entries[entry.traceId]
        require(existing == null || existing == entry) {
            "Causal trace already exists with different content: ${entry.traceId}"
        }
        entries[entry.traceId] = entry
    }

    @Synchronized
    override suspend fun load(traceId: CausalTraceId): CausalLedgerEntry? = entries[traceId]
}
