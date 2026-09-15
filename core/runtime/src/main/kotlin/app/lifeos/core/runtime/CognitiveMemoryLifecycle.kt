package app.lifeos.core.runtime

import app.lifeos.core.model.GraphActivityClass

data class MemoryClusterRef(val clusterId: String, val activityClass: GraphActivityClass, val fingerprint: String) {
    init { require(clusterId.isNotBlank() && fingerprint.isNotBlank()) }
}

/** Prefetch retrieves only; it never decides, mutates authority or commits a cognitive transaction. */
interface PredictiveContextPrefetcher {
    suspend fun prefetch(topicKeys: Set<String>, maxClusters: Int): List<MemoryClusterRef>
}

data class CognitiveGcPlan(
    val removableProjectionFingerprints: Set<String>,
    val retainedEvidenceFingerprints: Set<String>,
    val reconstructable: Boolean,
)

interface CognitiveGarbageCollector {
    suspend fun plan(): CognitiveGcPlan
    suspend fun compact(plan: CognitiveGcPlan) {
        require(plan.reconstructable) { "Cognitive GC may compact only reconstructable projections" }
    }
}
