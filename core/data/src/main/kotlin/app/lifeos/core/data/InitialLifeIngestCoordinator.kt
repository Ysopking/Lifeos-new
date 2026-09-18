package app.lifeos.core.data

import app.lifeos.core.runtime.CognitiveUtilityFunction
import app.lifeos.core.runtime.CognitiveUtilityInput

enum class IngestPass {
    INVENTORY,
    FINGERPRINT_DEDUP,
    METADATA_CLASSIFICATION,
    SEMANTIC_ENRICHMENT,
    ASSOCIATION,
    COLD_BACKGROUND,
}

data class IngestCandidate(
    val sourceId: LiveSourceId,
    val externalKey: String,
    val relevanceMicros: Long,
    val informationDensityMicros: Long,
    val connectivityMicros: Long,
    val accessibilityMicros: Long,
    val fingerprint: String? = null,
    val sourceType: String? = null,
    val observedAtEpochMillis: Long? = null,
    val relationKeys: Set<String> = emptySet(),
    val urgencyMicros: Long = 0L,
    val noveltyMicros: Long = 0L,
    val activeMatterAffinityMicros: Long = 0L,
    val goalAffinityMicros: Long = 0L,
    val confidenceMicros: Long = 1_000_000L,
    val authorityMicros: Long = 0L,
    val processingCostMicros: Long = 0L,
    val riskMicros: Long = 0L,
) {
    init {
        require(externalKey.isNotBlank())
        listOf(
            relevanceMicros,
            informationDensityMicros,
            connectivityMicros,
            accessibilityMicros,
            urgencyMicros,
            noveltyMicros,
            activeMatterAffinityMicros,
            goalAffinityMicros,
            confidenceMicros,
            authorityMicros,
            processingCostMicros,
            riskMicros,
        ).forEach { require(it in 0L..1_000_000L) }
        fingerprint?.let { require(it.isNotBlank()) }
        sourceType?.let { require(it.isNotBlank()) }
        observedAtEpochMillis?.let { require(it >= 0L) }
        require(relationKeys.none { it.isBlank() })
    }

    val priorityScore: Long get() = INGEST_UTILITY.score(
        CognitiveUtilityInput(
            relevanceMicros = relevanceMicros,
            urgencyMicros = urgencyMicros,
            informationGainMicros = informationDensityMicros,
            matterAffinityMicros = maxOf(activeMatterAffinityMicros, connectivityMicros),
            goalAffinityMicros = goalAffinityMicros,
            confidenceMicros = confidenceMicros,
            authorityMicros = authorityMicros,
            noveltyMicros = noveltyMicros,
            hardwareBudgetMicros = accessibilityMicros,
            costMicros = processingCostMicros,
            riskMicros = riskMicros,
        )
    )

    val stableIngestKey: String get() = fingerprint ?: "${sourceId.value}:$externalKey"
}

data class InitialIngestPlan(
    val pass: IngestPass,
    val candidates: List<IngestCandidate>,
    val deferredByBudget: Boolean = false,
)

data class InitialIngestRuntimeBudget(
    val deviceIdle: Boolean,
    val charging: Boolean,
    val thermalPressureMicros: Long,
    val memoryPressureMicros: Long,
    val semanticLimit: Int = 128,
    val associationLimit: Int = 256,
    val coldLimit: Int = 64,
) {
    init {
        require(thermalPressureMicros in 0L..1_000_000L)
        require(memoryPressureMicros in 0L..1_000_000L)
        require(semanticLimit > 0 && associationLimit > 0 && coldLimit > 0)
    }

    val allowColdBackground: Boolean
        get() = deviceIdle && charging && thermalPressureMicros < 550_000L && memoryPressureMicros < 700_000L
}

data class InitialIngestSchedule(
    val conversationAvailableImmediately: Boolean = true,
    val waves: List<InitialIngestPlan>,
) {
    init {
        require(conversationAvailableImmediately) { "Initial ingest must never gate conversation availability" }
        require(waves.map { it.pass }.distinct().size == waves.size)
    }
}

/** Progressive first-run ingest; deep/cold analysis is always subordinate to foreground conversation. */
class InitialLifeIngestCoordinator {
    fun plan(pass: IngestPass, candidates: Collection<IngestCandidate>, limit: Int): InitialIngestPlan {
        require(limit > 0)
        val deduplicated = if (pass == IngestPass.FINGERPRINT_DEDUP) deduplicate(candidates) else candidates.toList()
        val ordered = order(pass, deduplicated).take(limit)
        return InitialIngestPlan(pass, ordered)
    }

    fun schedule(
        candidates: Collection<IngestCandidate>,
        budget: InitialIngestRuntimeBudget,
    ): InitialIngestSchedule {
        val inventory = order(IngestPass.INVENTORY, candidates)
        val deduplicated = deduplicate(inventory)
        val metadata = order(IngestPass.METADATA_CLASSIFICATION, deduplicated)
        val semantic = order(IngestPass.SEMANTIC_ENRICHMENT, metadata).take(budget.semanticLimit)
        val associationPool = deduplicated.filter { it.relationKeys.isNotEmpty() || it.connectivityMicros > 0L }
        val associations = order(IngestPass.ASSOCIATION, associationPool).take(budget.associationLimit)
        val hotKeys = (semantic.asSequence() + associations.asSequence()).map { it.stableIngestKey }.toSet()
        val coldCandidates = order(IngestPass.COLD_BACKGROUND, deduplicated.filter { it.stableIngestKey !in hotKeys })
        val cold = if (budget.allowColdBackground) coldCandidates.take(budget.coldLimit) else emptyList()

        return InitialIngestSchedule(
            waves = listOf(
                InitialIngestPlan(IngestPass.INVENTORY, inventory),
                InitialIngestPlan(IngestPass.FINGERPRINT_DEDUP, deduplicated),
                InitialIngestPlan(IngestPass.METADATA_CLASSIFICATION, metadata),
                InitialIngestPlan(IngestPass.SEMANTIC_ENRICHMENT, semantic),
                InitialIngestPlan(IngestPass.ASSOCIATION, associations),
                InitialIngestPlan(
                    pass = IngestPass.COLD_BACKGROUND,
                    candidates = cold,
                    deferredByBudget = !budget.allowColdBackground && coldCandidates.isNotEmpty(),
                ),
            ),
        )
    }

    private fun deduplicate(candidates: Collection<IngestCandidate>): List<IngestCandidate> =
        candidates
            .groupBy { it.stableIngestKey }
            .values
            .map { duplicates ->
                duplicates.maxWithOrNull(
                    compareBy<IngestCandidate> { it.priorityScore }
                        .thenBy { it.observedAtEpochMillis ?: Long.MIN_VALUE }
                        .thenByDescending { it.sourceId.value }
                        .thenByDescending { it.externalKey },
                ) ?: error("Empty ingest duplicate group")
            }
            .sortedWith(stableOrdering())

    private fun order(pass: IngestPass, candidates: Collection<IngestCandidate>): List<IngestCandidate> {
        val comparator = when (pass) {
            IngestPass.ASSOCIATION -> compareByDescending<IngestCandidate> { it.connectivityMicros }
                .thenByDescending { it.relationKeys.size }
                .thenByDescending { it.priorityScore }
                .then(stableOrdering())
            IngestPass.COLD_BACKGROUND -> compareByDescending<IngestCandidate> { it.priorityScore }
                .then(stableOrdering())
            else -> compareByDescending<IngestCandidate> { it.priorityScore }
                .then(stableOrdering())
        }
        return candidates.sortedWith(comparator)
    }

    private fun stableOrdering(): Comparator<IngestCandidate> =
        compareBy<IngestCandidate> { it.sourceId.value }.thenBy { it.externalKey }
}


private val INGEST_UTILITY = CognitiveUtilityFunction()
