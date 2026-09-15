package app.lifeos.core.model

data class EpistemicProfile(
    val domain: String,
    val expertiseMicros: Long,
    val calibrationMicros: Long,
    val coverageMicros: Long,
    val reliabilityMicros: Long,
) {
    init {
        require(domain.isNotBlank())
        listOf(expertiseMicros, calibrationMicros, coverageMicros, reliabilityMicros).forEach { require(it in 0..1_000_000) }
    }
    val fitMicros: Long get() = (expertiseMicros + calibrationMicros + coverageMicros + reliabilityMicros) / 4L
}

data class ModuleRouteCandidate(
    val module: ModuleIdentity,
    val profile: EpistemicProfile,
    val estimatedCostMicros: Long,
    val complementaryTags: Set<String> = emptySet(),
) { init { require(estimatedCostMicros >= 0) } }

data class CognitiveResourceBudget(
    val maxModules: Int,
    val maxCostMicros: Long,
    val deadlineMillis: Long,
    val maxIterations: Int,
) {
    init { require(maxModules > 0); require(maxCostMicros >= 0); require(deadlineMillis > 0); require(maxIterations > 0) }
}

data class ModuleCoalition(val modules: List<ModuleIdentity>, val estimatedCostMicros: Long)

class EpistemicCoalitionSelector {
    fun select(candidates: Collection<ModuleRouteCandidate>, budget: CognitiveResourceBudget): ModuleCoalition {
        var cost = 0L
        val covered = linkedSetOf<String>()
        val selected = mutableListOf<ModuleRouteCandidate>()
        candidates.sortedWith(
            compareByDescending<ModuleRouteCandidate> { it.profile.fitMicros }
                .thenBy { it.estimatedCostMicros }
                .thenBy { it.module.stableFingerprint }
        ).forEach { candidate ->
            if (selected.size >= budget.maxModules) return@forEach
            val addsDiversity = candidate.complementaryTags.isEmpty() || candidate.complementaryTags.any { it !in covered }
            val fits = cost + candidate.estimatedCostMicros <= budget.maxCostMicros
            if (addsDiversity && fits) {
                selected += candidate
                cost += candidate.estimatedCostMicros
                covered += candidate.complementaryTags
            }
        }
        return ModuleCoalition(selected.map { it.module }, cost)
    }
}

enum class CognitiveWorkPriority { FOREGROUND_CONVERSATION, CRITICAL_LIFE_EVENT, INTERACTIVE_GENERATION, INGEST, CONSOLIDATION, CREATIVE_BUILD_EVOLUTION }

data class AnytimeWorldBudget(
    val priority: CognitiveWorkPriority,
    val timeSliceMillis: Long,
    val maxNodes: Int,
    val maxEdges: Int,
    val maxIterations: Int,
    val approximateAllowed: Boolean,
) {
    init { require(timeSliceMillis > 0); require(maxNodes > 0); require(maxEdges > 0); require(maxIterations > 0) }
}

object AnytimeWorldBudgetPolicy {
    fun forLatency(priority: CognitiveWorkPriority, availableMillis: Long): AnytimeWorldBudget {
        val ms = availableMillis.coerceAtLeast(1L)
        return when {
            ms <= 5 -> AnytimeWorldBudget(priority, ms, 64, 128, 1, true)
            ms <= 20 -> AnytimeWorldBudget(priority, ms, 256, 768, 3, true)
            ms <= 100 -> AnytimeWorldBudget(priority, ms, 1_024, 4_096, 8, false)
            else -> AnytimeWorldBudget(priority, ms, 8_192, 32_768, 32, false)
        }
    }
}
