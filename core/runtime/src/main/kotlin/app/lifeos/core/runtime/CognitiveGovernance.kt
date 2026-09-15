package app.lifeos.core.runtime

import app.lifeos.core.model.CognitiveTransactionId

enum class CognitiveInvariantKind { IDENTITY, REVISION, AUTHORITY, CAUSALITY, REPLAY, SECURITY, RESOURCE, CONVERSATION_PREEMPTION }
data class CognitiveInvariant(val id: String, val kind: CognitiveInvariantKind, val description: String) {
    init { require(id.isNotBlank() && description.isNotBlank()) }
}

class CognitiveInvariantRegistry(invariants: Collection<CognitiveInvariant>) {
    private val byId = invariants.associateBy { it.id }
    init { require(byId.size == invariants.size) { "Invariant ids must be unique" } }
    fun all(): List<CognitiveInvariant> = byId.values.sortedBy { it.id }
}

data class TransactionCapabilities(val transactionId: CognitiveTransactionId, val capabilityIds: Set<String>) {
    init { require(capabilityIds.none { it.isBlank() }) }
    fun permits(capabilityId: String): Boolean = capabilityId in capabilityIds
}

data class CognitiveTelemetryProjection(
    val transactionId: CognitiveTransactionId?,
    val latencyMillis: Long,
    val activeModules: Int,
    val graphNodes: Int,
    val graphEdges: Int,
    val convergenceIterations: Int,
    val memoryHits: Long,
    val cacheHits: Long,
    val hardwarePressureMicros: Long,
    val committed: Boolean,
) {
    init {
        require(latencyMillis >= 0 && activeModules >= 0 && graphNodes >= 0 && graphEdges >= 0 && convergenceIterations >= 0)
        require(memoryHits >= 0 && cacheHits >= 0)
        require(hardwarePressureMicros in 0..1_000_000L)
    }
}
