package app.lifeos.core.data

enum class IngestPass { INVENTORY, FINGERPRINT_DEDUP, METADATA_CLASSIFICATION, SEMANTIC_ENRICHMENT }

data class IngestCandidate(
    val sourceId: LiveSourceId,
    val externalKey: String,
    val relevanceMicros: Long,
    val informationDensityMicros: Long,
    val connectivityMicros: Long,
    val accessibilityMicros: Long,
) {
    init {
        require(externalKey.isNotBlank())
        listOf(relevanceMicros, informationDensityMicros, connectivityMicros, accessibilityMicros)
            .forEach { require(it in 0..1_000_000L) }
    }
    val priorityScore: Long get() =
        listOf(relevanceMicros, informationDensityMicros, connectivityMicros, accessibilityMicros).sum()
}

data class InitialIngestPlan(val pass: IngestPass, val candidates: List<IngestCandidate>)

/** Keeps conversation availability independent from deep first-run enrichment. */
class InitialLifeIngestCoordinator {
    fun plan(pass: IngestPass, candidates: Collection<IngestCandidate>, limit: Int): InitialIngestPlan {
        require(limit > 0)
        val ordered = candidates.sortedWith(
            compareByDescending<IngestCandidate> { it.priorityScore }
                .thenBy { it.sourceId.value }
                .thenBy { it.externalKey }
        ).take(limit)
        return InitialIngestPlan(pass, ordered)
    }
}
