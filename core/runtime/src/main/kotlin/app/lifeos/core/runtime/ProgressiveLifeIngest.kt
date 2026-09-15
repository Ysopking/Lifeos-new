package app.lifeos.core.runtime

/** Progressive ingest never gates conversation readiness on deep enrichment. */
enum class ProgressiveIngestPhase { READY, ORIENTATION, STRUCTURE, MEANING, CONNECTIONS, BACKGROUND_COMPLETION }

data class IngestCandidate(
    val id: String,
    val relevanceMicros: Long,
    val informationDensityMicros: Long,
    val connectivityMicros: Long,
    val accessibilityMicros: Long,
    val urgencyMicros: Long = 0,
    val noveltyMicros: Long = 0,
    val activeMatterAffinityMicros: Long = 0,
    val processingCostMicros: Long = 0,
)

class ProgressiveIngestPrioritizer {
    fun score(candidate: IngestCandidate): Long = listOf(
        candidate.relevanceMicros,
        candidate.informationDensityMicros,
        candidate.connectivityMicros,
        candidate.accessibilityMicros,
        candidate.urgencyMicros,
        candidate.noveltyMicros,
        candidate.activeMatterAffinityMicros,
    ).fold(0L, ::satAdd).let { positive -> (positive - candidate.processingCostMicros).coerceAtLeast(0L) }

    fun order(candidates: Collection<IngestCandidate>): List<IngestCandidate> =
        candidates.sortedWith(compareByDescending<IngestCandidate>(::score).thenBy { it.id })

    private fun satAdd(a: Long, b: Long): Long = if (b > 0 && Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
}

data class BackgroundIngestBudget(
    val idle: Boolean,
    val charging: Boolean,
    val thermalAllowed: Boolean,
    val memoryAllowed: Boolean,
) { val deepWorkAllowed: Boolean get() = idle && charging && thermalAllowed && memoryAllowed }
