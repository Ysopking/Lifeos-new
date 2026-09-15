package app.lifeos.core.runtime

import app.lifeos.core.model.*

enum class CognitivePath { FAST, DEEP }

data class CognitiveAttentionBudget(
    val maxActivePhotons: Int,
    val maxActiveModules: Int,
    val maxMillis: Long,
) { init { require(maxActivePhotons > 0 && maxActiveModules > 0 && maxMillis > 0) } }

data class ActiveWorldSubgraph(
    val photonKeys: Set<String>,
    val memoryKeys: Set<String>,
    val goalKeys: Set<String>,
    val moduleFingerprints: Set<String>,
    val dependencyFingerprints: Set<String>,
) {
    init { require((photonKeys + memoryKeys + goalKeys + moduleFingerprints + dependencyFingerprints).none { it.isBlank() }) }
    val nodeCount: Int get() = photonKeys.size + memoryKeys.size + goalKeys.size + moduleFingerprints.size
}

data class CognitiveRoutingDecision(
    val path: CognitivePath,
    val attention: CognitiveAttentionBudget,
    val activeGraph: ActiveWorldSubgraph,
    val escalateForConflict: Boolean,
    val escalateForUncertainty: Boolean,
)

/** FAST is the default conversation path. DEEP requires evidence of complexity/conflict/uncertainty. */
class ConversationCognitiveRouter(
    private val deepUncertaintyThresholdMicros: Long = 650_000L,
    private val deepConflictThresholdMicros: Long = 500_000L,
) {
    fun route(
        semantic: SemanticTurn,
        context: ConversationWorkingContext,
        conflicts: Collection<ConflictActivation>,
        candidateModuleFingerprints: Set<String>,
        dependencyFingerprints: Set<String>,
    ): CognitiveRoutingDecision {
        val conflictEnergy = conflicts.maxOfOrNull { it.conflictEnergyMicros } ?: 0L
        val deepForUncertainty = semantic.uncertainty.maximum >= deepUncertaintyThresholdMicros
        val deepForConflict = conflictEnergy >= deepConflictThresholdMicros
        val trivial = semantic.act in setOf(SemanticAct.CONFIRMATION, SemanticAct.CONTINUATION) && !deepForUncertainty && !deepForConflict
        val path = if (trivial || (!deepForUncertainty && !deepForConflict && semantic.act != SemanticAct.TASK)) CognitivePath.FAST else CognitivePath.DEEP
        val attention = if (path == CognitivePath.FAST) CognitiveAttentionBudget(256, 6, 350) else CognitiveAttentionBudget(2048, 24, 4_000)
        val graph = ActiveWorldSubgraph(
            photonKeys = setOf(semantic.textFingerprint),
            memoryKeys = context.relevantMemoryKeys.take(attention.maxActivePhotons - 1).toSet(),
            goalKeys = context.activeGoalId?.let(::setOf) ?: emptySet(),
            moduleFingerprints = candidateModuleFingerprints.sorted().take(attention.maxActiveModules).toSet(),
            dependencyFingerprints = dependencyFingerprints,
        )
        return CognitiveRoutingDecision(path, attention, graph, deepForConflict, deepForUncertainty)
    }
}

data class ModuleEpistemicFit(
    val moduleFingerprint: String,
    val expertiseMicros: Long,
    val calibrationMicros: Long,
    val coverageMicros: Long,
    val reliabilityMicros: Long,
    val noveltyMicros: Long,
) {
    init { require(moduleFingerprint.isNotBlank()); listOf(expertiseMicros, calibrationMicros, coverageMicros, reliabilityMicros, noveltyMicros).forEach { require(it in 0..1_000_000L) } }
    val fitMicros: Long get() = (expertiseMicros + calibrationMicros + coverageMicros + reliabilityMicros + noveltyMicros) / 5
}

class EpistemicModuleRouter {
    fun select(candidates: Collection<ModuleEpistemicFit>, maxModules: Int): List<ModuleEpistemicFit> {
        require(maxModules > 0)
        return candidates.sortedWith(compareByDescending<ModuleEpistemicFit> { it.fitMicros }.thenBy { it.moduleFingerprint }).take(maxModules)
    }
}
